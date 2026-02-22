package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GeminiProperties;
import com.ai.chat.constants.ApplicationConstants;
import com.ai.chat.dto.GeminiGenerationConfigDto;
import com.ai.chat.dto.GeminiRequestDto;
import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatMessage;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.records.GeminiMessagesRecord;
import com.ai.chat.records.GeminiPartsRecord;
import com.ai.chat.records.GeminiTextRecord;
import com.ai.chat.repositories.ChatMessageRepository;
import com.ai.chat.repositories.ChatSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@Component
public class GeminiClient implements LLMClient {

    private final WebClient webClient;
    private final GeminiProperties geminiProperties;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    ChatSessionRepository chatSessionRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(GeminiProperties geminiProperties, WebClient webClient) {
        this.geminiProperties = geminiProperties;
        this.webClient = webClient;
    }


    @Override
    public Flux<String> stream(String prompt, UserContext userContext, ChatSession chatSession) {

        String user = userContext.getUserId();
        Long sessionId = chatSession.getSessionId();
        SessionHistory.addMessage(user, sessionId, "user", prompt);
        StringBuilder aiBuffer = new StringBuilder();

        String url = UriComponentsBuilder.fromUriString(geminiProperties.getBaseUrl())
                .pathSegment(geminiProperties.getModel(), ":generateContent")
                .queryParam("alt", "sse")
                .toUriString();

        return webClient.post()
                .uri(url)
                .headers(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                    httpHeaders.set("x-goog-api-key", geminiProperties.getApiKey());
                })
                .bodyValue(buildRequest(SessionHistory.getHistory(user, sessionId), userContext.getUserName()))
                .retrieve()
                .bodyToFlux(String.class)
                .checkpoint("AI_STREAM_START")
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(throwable -> throwable instanceof WebClientRequestException))
                .doOnError(e -> System.err.println("Stream failed after retries: " + e.getMessage()))
                .flatMap(chunk -> {
                    if (chunk.contains("[DONE]")) return Flux.just("{\"done\":true}");
                    try {
                        int start = chunk.indexOf("{");
                        if (start == -1) return Flux.empty();

                        JsonNode root = mapper.readTree(chunk.substring(start));
                        String content = root.path("candidates").get(0)
                                .at("/content/parts").get(0)
                                .path("text").asString(""); // Use .asText()

                        if (content.isEmpty()) return Flux.empty();

                        aiBuffer.append(content);

                        String[] words = content.split("(?<= )");

                        return Flux.fromArray(words).map(word -> {
                            ObjectNode response = mapper.createObjectNode();
                            response.put("content", word);
                            return response.toString();
                        });
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                })
                .delayElements(Duration.ofMillis(30))
                .doFinally(signalType -> {
                    SessionHistory.addMessage(user, sessionId, "assistant", aiBuffer.toString());

                    ChatMessage userPrompt = ChatMessage.builder().
                            role("user")
                            .content(prompt)
                            .session(chatSession)
                            .build();

                    ChatMessage aiMsg = ChatMessage.builder()
                            .role("assistant")
                            .content(aiBuffer.toString())
                            .session(chatSession)
                            .build();

                    Mono.fromCallable(() -> chatMessageRepository.saveAll(List.of(userPrompt, aiMsg)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();

                    if (chatSession.getTitle() == null || chatSession.getTitle().isBlank()) {
                        generateTitle(prompt, chatSession);
                    }
                });

    }

    @Override
    public void generateTitle(String prompt, ChatSession session) {
        String summarizationPrompt = "Generate a concise 3-word title for: '" + prompt + "'";

        String url = UriComponentsBuilder.fromUriString(geminiProperties.getBaseUrl())
                .pathSegment(geminiProperties.getModel(), ":generateContent")
                .toUriString();

        webClient.post()
                .uri(url)
                .headers(httpHeaders -> {
                    httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                    httpHeaders.set("x-goog-api-key", geminiProperties.getApiKey());
                })
                .bodyValue(buildTitleRequest(summarizationPrompt))
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractText)
                .map(title -> title.replaceAll("\"", "").trim())
                .flatMap(cleanTitle -> {
                    String safeTitle = cleanTitle.length() > 60
                            ? cleanTitle.substring(0, 60) + "..."
                            : cleanTitle;
                    session.setTitle(safeTitle);
                    return Mono.fromCallable(() -> chatSessionRepository.save(session))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> System.err.println("Title generation failed: " + e.getMessage()))
                .subscribe();
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    private GeminiRequestDto buildRequest(Deque<ObjectNode> history, String username) {

        String userSystem = ApplicationConstants.SYSTEM_PROMPT + String.format(" - User name is %s", username);

        List<GeminiMessagesRecord> geminiMessagesRecords = history
                .stream()
                .map(msg -> {
                    // Safely extract the exact text without Jackson adding extra quotes
                    String originalRole = msg.path("role").asString("user");
                    String content = msg.path("content").asString("");

                    // Map to Gemini's strict roles
                    String geminiRole = originalRole.equals("assistant") ? "model" : "user";

                    return GeminiMessagesRecord.builder()
                            .role(geminiRole)
                            .parts(List.of(
                                    GeminiTextRecord.builder()
                                            .text(content)
                                            .build()
                            )).build();
                }).toList();

        return GeminiRequestDto.builder()
                .systemInstruction(GeminiPartsRecord.builder()
                        .parts(List.of(GeminiTextRecord.builder()
                                .text(userSystem).build())
                        ).build())
                .contents(geminiMessagesRecords)
                .build();
    }

    private GeminiRequestDto buildTitleRequest(String prompt) {

        String instruction = "Summarize this into a 3-word title: " + prompt +
                " Plain text ONLY.  Strictly NO markdown, NO bolding, NO quotes, and NO periods.";

        return GeminiRequestDto.builder()
                .contents(
                        List.of(GeminiMessagesRecord.builder()
                                .role("user")
                                .parts(
                                        List.of(GeminiTextRecord.builder()
                                                .text(instruction)
                                                .build()))
                                .build()))
                .generationConfig(
                        GeminiGenerationConfigDto.builder()
                                .maxOutputTokens(20)
                                .temperature(1.0)
                                .build()
                ).build();
    }

    private String extractText(Map response) {
        try {
            List candidates = (List) response.get("candidates");
            Map firstCandidate = (Map) candidates.get(0);
            Map content = (Map) firstCandidate.get("content");
            List parts = (List) content.get("parts");
            Map firstPart = (Map) parts.get(0);
            return (String) firstPart.get("text");
        } catch (Exception e) {
            return "Untitled Conversation";
        }
    }
}
