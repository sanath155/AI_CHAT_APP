package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GroqProperties;
import com.ai.chat.constants.ApplicationConstants;
import com.ai.chat.dto.GroqRequestDto;
import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatMessage;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.records.GroqMessagesRecord;
import com.ai.chat.repositories.ChatMessageRepository;
import com.ai.chat.repositories.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Component
public class GroqClient implements LLMClient {

    private final WebClient webClient;
    private final GroqProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    ChatSessionRepository chatSessionRepository;

    public GroqClient(GroqProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }


    @Override
    public String getProviderName() {
        return "groq";
    }

    @Override
    public Flux<String> stream(String prompt, UserContext userContext, ChatSession chatSession) {
        String user = userContext.getUserId();
        Long sessionId = chatSession.getSessionId();
        SessionHistory.addMessage(user, sessionId, "user", prompt);
        StringBuffer aiResponseBuffer = new StringBuffer();

        return webClient.post()
                .uri(properties.getBaseUrl() + "/chat/completions")
                .headers(httpHeaders -> {
                    httpHeaders.setBearerAuth(properties.getApiKey());
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                })
                .bodyValue(buildRequest(SessionHistory.getHistory(user, sessionId), userContext.getUserName()))
                .retrieve()
                .bodyToFlux(String.class)
                .checkpoint("AI_STREAM_START")
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> throwable instanceof WebClientRequestException))
                .doOnError(e -> System.err.println("Stream failed after retries: " + e.getMessage()))
                .map(chunk -> {
                    if (chunk.contains("[DONE]")) return "{\"done\":true}";

                    try {
                        int start = chunk.indexOf("{");
                        if (start == -1) return "";

                        JsonNode root = mapper.readTree(chunk.substring(start));

                        String content = root.path("choices").get(0).path("delta").path("content").asString("");

                        aiResponseBuffer.append(content);

                        ObjectNode response = mapper.createObjectNode();
                        response.put("content", content);

                        return response.toString();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(text -> !text.isEmpty())
                // delayElements gives that smooth typing effect
                .delayElements(Duration.ofMillis(30))
                .doFinally(signalType -> {

                    String finalAiContent = aiResponseBuffer.toString();

                    if (!finalAiContent.isEmpty()) {
                        SessionHistory.addMessage(user, sessionId, "assistant", finalAiContent);

                        ChatMessage userMsg = ChatMessage.builder().role("user").content(prompt).session(chatSession).build();
                        ChatMessage aiMsg = ChatMessage.builder().role("assistant").content(finalAiContent).session(chatSession).build();

                        Mono.fromCallable(() -> chatMessageRepository.saveAll(List.of(userMsg, aiMsg)))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe();

                        if (chatSession.getTitle() == null || chatSession.getTitle().isBlank()) {
                            generateTitle(prompt, chatSession);
                        }
                    }
                });
    }

    @Override
    public void generateTitle(String prompt, ChatSession session) {
        webClient.post().
                uri(properties.getBaseUrl() + "/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .bodyValue(buildTitleRequest(prompt))
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractGroqText)
                .map(title -> title.replaceAll("\"", "").trim())
                .flatMap(cleanTitle -> {
                    String safeTitle = cleanTitle.length() > 30
                            ? cleanTitle.substring(0, 30) + "..."
                            : cleanTitle;
                    session.setTitle(safeTitle);
                    return Mono.fromCallable(() -> chatSessionRepository.save(session))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> System.err.println("Groq Title generation failed: " + e.getMessage()))
                .subscribe();
    }

    private GroqRequestDto buildRequest(Deque<ObjectNode> history, String username) {
        String userSystem = ApplicationConstants.SYSTEM_PROMPT + String.format(" - User name is %s", username);
        List<GroqMessagesRecord> groqMessagesRecords = new ArrayList<>();
        groqMessagesRecords.add(GroqMessagesRecord.builder()
                .role("system")
                .content(userSystem).build());

        history.forEach(objectNode -> groqMessagesRecords.add(GroqMessagesRecord.builder()
                .role(objectNode.get("role").asString())
                .content(objectNode.get("content").asString()).build()));

        return GroqRequestDto.builder()
                .model(properties.getModel())
                .stream(true)
                .messages(groqMessagesRecords)
                .temperature(0.2)
                .top_p(0.9)
                .build();
    }

    private GroqRequestDto buildTitleRequest(String prompt) {

        List<GroqMessagesRecord> titleMessages = List.of(GroqMessagesRecord.builder()
                        .role("system")
                        .content(ApplicationConstants.TITLE_PROMPT)
                        .build()
                , GroqMessagesRecord.builder()
                        .role("user")
                        .content(prompt)
                        .build());

        return GroqRequestDto.builder()
                .model(properties.getModel())
                .stream(false)
                .messages(titleMessages)
                .temperature(0.1)
                .build();
    }

    private String extractGroqText(Map response) {
        try {
            List choices = (List) response.get("choices");
            Map firstChoice = (Map) choices.get(0);
            Map message = (Map) firstChoice.get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            return "Untitled Conversation";
        }
    }

}
