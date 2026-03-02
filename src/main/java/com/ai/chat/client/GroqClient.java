package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GroqProperties;
import com.ai.chat.constants.ApplicationConstants;
import com.ai.chat.dto.ChatSessionDto;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GroqClient implements LLMClient {

    private final WebClient webClient;
    private final GroqProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    ChatMessageRepository chatMessageRepository;
    @Autowired
    ChatSessionRepository chatSessionRepository;
    @Autowired
    SessionHistory sessionHistory;

    public GroqClient(GroqProperties properties, WebClient webClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderName() {
        return "groq";
    }

    @Override
    public Flux<String> stream(String prompt, UserContext userContext, Long sessionId) {
        String userId = userContext.getUserId();
        StringBuilder aiResponseBuffer = new StringBuilder();

        return buildRequest(userId, sessionId, userContext.getUserName(), prompt)
                .flatMapMany(groqRequestDto -> webClient.post()
                        .uri(properties.getBaseUrl() + "/chat/completions")
                        .headers(httpHeaders -> {
                            httpHeaders.setBearerAuth(properties.getApiKey());
                            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
                            httpHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                        })
                        .bodyValue(groqRequestDto)
                        .retrieve()
                        .bodyToFlux(String.class)
                )
                .flatMap(chunk -> {
                    String[] events = chunk.split("data:");

                    return Flux.fromArray(events)
                            .filter(event -> !event.isBlank() && !event.contains("[DONE]"))
                            .map(event -> {
                                try {
                                    int start = event.indexOf("{");
                                    if (start == -1) return "";

                                    JsonNode root = objectMapper.readTree(event.substring(start));
                                    return root.path("choices").get(0)
                                            .path("delta")
                                            .path("content").asString("");
                                } catch (Exception e) {
                                    return "";
                                }
                            })
                            .filter(content -> !content.isEmpty())
                            .map(content -> {
                                aiResponseBuffer.append(content);

                                ObjectNode response = objectMapper.createObjectNode();
                                response.put("content", content);
                                return response.toString();
                            });
                })
                .delayElements(Duration.ofMillis(30))
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        handlePostStreamActions(userId, sessionId, prompt, aiResponseBuffer.toString());
                    }
                });
    }

    private void handlePostStreamActions(String userId, Long sessionId, String prompt, String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) return;

        ChatSession sessionProxy = chatSessionRepository.getReferenceById(sessionId);
        ChatMessage userMsg = ChatMessage.builder().role("user").content(prompt).session(sessionProxy).build();
        ChatMessage aiMsg = ChatMessage.builder().role("assistant").content(aiResponse).session(sessionProxy).build();

        Mono<List<ChatMessage>> dbSaveMono = Mono.fromCallable(() -> chatMessageRepository.saveAll(List.of(userMsg, aiMsg)))
                .subscribeOn(Schedulers.boundedElastic());

        Mono<Long> cacheUserMono = sessionHistory.addMessageInCache(userId, sessionId, "user", prompt);
        Mono<Long> cacheAiMono = sessionHistory.addMessageInCache(userId, sessionId, "assistant", aiResponse);

        Mono.when(dbSaveMono, cacheUserMono, cacheAiMono)
                .then(sessionHistory.getSession(userId, String.valueOf(sessionId))
                        .switchIfEmpty(Mono.fromCallable(() -> chatSessionRepository.findById(sessionId))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty))
                                .map(chatSession -> new ChatSessionDto(
                                        chatSession.getSessionId(),
                                        chatSession.getCreatedDate(),
                                        chatSession.getTitle()))
                        )
                )
                .flatMap(dto -> {
                    if (dto.getTitle() == null || dto.getTitle().isBlank() || dto.getTitle().equals("New Chat")) {
                        return generateTitle(userId, prompt, dto);
                    } else {
                        return sessionHistory.updateOrAddSession(userId, dto).thenReturn(dto);
                    }
                })
                .subscribe();
    }

    @Override
    public Mono<ChatSessionDto> generateTitle(String userId, String prompt, ChatSessionDto dto) {
        String url = properties.getBaseUrl() + "/chat/completions";

        return webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .bodyValue(buildTitleRequest(prompt))
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractGroqText)
                .map(title -> title.replaceAll("\"", "").trim())
                .flatMap(cleanTitle -> {
                    String safeTitle = cleanTitle.length() > 30 ? cleanTitle.substring(0, 30) + "..." : cleanTitle;
                    dto.setTitle(safeTitle);

                    return Mono.when(
                            Mono.fromRunnable(() -> chatSessionRepository.updateTitle(dto.getSessionId(), safeTitle)).subscribeOn(Schedulers.boundedElastic()),
                            sessionHistory.updateOrAddSession(userId, dto)
                    ).thenReturn(dto);
                })
                .doOnError(e -> System.err.println("Groq Title generation failed: " + e.getMessage()));
    }

    private Mono<GroqRequestDto> buildRequest(String userId, Long sessionId, String username, String prompt) {
        return sessionHistory.getLastLimitChat(userId, sessionId)
                .map(chatMessageDtoList -> {
                    String userSystem = ApplicationConstants.SYSTEM_PROMPT + String.format(" - User name is %s", username);
                    List<GroqMessagesRecord> groqMessagesRecords = new ArrayList<>();
                    groqMessagesRecords.add(GroqMessagesRecord.builder().role("system").content(userSystem).build());
                    chatMessageDtoList.forEach(objectNode -> groqMessagesRecords.add(GroqMessagesRecord.builder().role(objectNode.getRole()).content(objectNode.getContent()).build()));
                    groqMessagesRecords.add(GroqMessagesRecord.builder().role("user").content(prompt).build());

                    return GroqRequestDto.builder()
                            .model(properties.getModel())
                            .stream(true)
                            .messages(groqMessagesRecords)
                            .temperature(0.2)
                            .top_p(0.9)
                            .build();
                });
    }

    private GroqRequestDto buildTitleRequest(String prompt) {
        List<GroqMessagesRecord> titleMessages = List.of(
                GroqMessagesRecord.builder().role("system").content(ApplicationConstants.TITLE_PROMPT).build(),
                GroqMessagesRecord.builder().role("user").content(prompt).build()
        );
        return GroqRequestDto.builder().model(properties.getModel()).stream(false).messages(titleMessages).temperature(0.1).build();
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