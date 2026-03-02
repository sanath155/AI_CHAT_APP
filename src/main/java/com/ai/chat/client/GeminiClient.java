package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GeminiProperties;
import com.ai.chat.constants.ApplicationConstants;
import com.ai.chat.dto.ChatSessionDto;
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
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static reactor.netty.http.HttpConnectionLiveness.log;

@Component
public class GeminiClient implements LLMClient {

    private final WebClient webClient;
    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    ChatMessageRepository chatMessageRepository;
    @Autowired
    ChatSessionRepository chatSessionRepository;
    @Autowired
    SessionHistory sessionHistory;

    public GeminiClient(GeminiProperties geminiProperties, WebClient webClient, ObjectMapper objectMapper) {
        this.geminiProperties = geminiProperties;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<String> stream(String prompt, UserContext userContext, Long sessionId) {
        String userId = userContext.getUserId();
        StringBuilder aiBuffer = new StringBuilder();

        return buildRequest(userId, sessionId, userContext.getUserName(), prompt)
                .flatMapMany(requestBody -> {
                    String url = UriComponentsBuilder.fromUriString(geminiProperties.getBaseUrl())
                            .pathSegment(geminiProperties.getModel() + ":generateContent")
                            .queryParam("alt", "sse")
                            .toUriString();

                    return webClient.post()
                            .uri(url)
                            .headers(h -> {
                                h.setContentType(MediaType.APPLICATION_JSON);
                                h.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
                                h.set("x-goog-api-key", geminiProperties.getApiKey());
                            })
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToFlux(String.class);
                })
                .flatMap(chunk -> {
                    String json = chunk.startsWith("data:") ? chunk.substring(5).trim() : chunk;
                    if (json.isEmpty() || json.contains("[DONE]")) return Flux.empty();

                    try {
                        JsonNode root = objectMapper.readTree(json);
                        String content = root.path("candidates").get(0)
                                .path("content").path("parts").get(0)
                                .path("text").asString("");

                        if (content.isEmpty()) return Flux.empty();
                        aiBuffer.append(content);

                        return Flux.fromArray(content.split("(?<= )")).map(word -> {
                            ObjectNode response = objectMapper.createObjectNode();
                            response.put("content", word);
                            response.put("sessionId", sessionId);
                            return response.toString();
                        });
                    } catch (Exception e) {
                        return Flux.empty();
                    }
                })
                .delayElements(Duration.ofMillis(20))
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        handlePostStreamActions(userId, sessionId, prompt, aiBuffer.toString());
                    }
                });
    }

    private void handlePostStreamActions(String userId, Long sessionId, String prompt, String aiResponse) {
        if (aiResponse == null || aiResponse.isBlank()) return;

        ChatSession sessionProxy = chatSessionRepository.getReferenceById(sessionId);
        ChatMessage userMsg = ChatMessage.builder().role("user").content(prompt).session(sessionProxy).build();
        ChatMessage aiMsg = ChatMessage.builder().role("assistant").content(aiResponse).session(sessionProxy).build();

        // Optimization: Parallel DB and Redis execution
        Mono<List<ChatMessage>> dbMono = Mono.fromCallable(() -> chatMessageRepository.saveAll(List.of(userMsg, aiMsg)))
                .subscribeOn(Schedulers.boundedElastic());

        Mono<Long> redisUser = sessionHistory.addMessageInCache(userId, sessionId, "user", prompt);
        Mono<Long> redisAi = sessionHistory.addMessageInCache(userId, sessionId, "assistant", aiResponse);

        Mono.when(dbMono, redisUser, redisAi)
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
        String summarizationPrompt = "Generate a concise 3-word title for: '" + prompt + "'";
        String url = UriComponentsBuilder.fromUriString(geminiProperties.getBaseUrl())
                .pathSegment(geminiProperties.getModel(), ":generateContent")
                .toUriString();

        return webClient.post()
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
                    String safeTitle = cleanTitle.length() > 60 ? cleanTitle.substring(0, 60) + "..." : cleanTitle;
                    dto.setTitle(safeTitle);

                    // Parallelize updates
                    return Mono.when(
                            Mono.fromRunnable(() -> chatSessionRepository.updateTitle(dto.getSessionId(), safeTitle)).subscribeOn(Schedulers.boundedElastic()),
                            sessionHistory.updateOrAddSession(userId, dto)
                    ).thenReturn(dto);
                })
                .doOnError(e -> log.error("Title generation failed for session {}: {}", dto.getSessionId(), e.getMessage()));
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    private Mono<GeminiRequestDto> buildRequest(String userId, Long sessionId, String username, String prompt) {
        return sessionHistory.getLastLimitChat(userId, sessionId)
                .map(history -> {
                    String userSystem = ApplicationConstants.SYSTEM_PROMPT + String.format(" - User name is %s", username);
                    List<GeminiMessagesRecord> records = history.stream()
                            .map(msg -> GeminiMessagesRecord.builder()
                                    .role(msg.getRole().equals("assistant") ? "model" : "user")
                                    .parts(List.of(GeminiTextRecord.builder().text(msg.getContent()).build()))
                                    .build())
                            .collect(Collectors.toList());

                    records.add(GeminiMessagesRecord.builder().role("user").parts(List.of(GeminiTextRecord.builder().text(prompt).build())).build());

                    return GeminiRequestDto.builder()
                            .systemInstruction(GeminiPartsRecord.builder().parts(List.of(GeminiTextRecord.builder().text(userSystem).build())).build())
                            .contents(records)
                            .build();
                });
    }

    private GeminiRequestDto buildTitleRequest(String prompt) {
        String instruction = "Summarize this into a 3-word title: " + prompt + " Plain text ONLY. Strictly NO markdown, NO bolding, NO quotes, and NO periods.";
        return GeminiRequestDto.builder()
                .contents(List.of(GeminiMessagesRecord.builder().role("user").parts(List.of(GeminiTextRecord.builder().text(instruction).build())).build()))
                .generationConfig(GeminiGenerationConfigDto.builder().maxOutputTokens(20).temperature(1.0).build())
                .build();
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