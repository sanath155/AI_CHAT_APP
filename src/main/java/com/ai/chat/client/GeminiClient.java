package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GeminiProperties;
import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatMessage;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.repositories.ChatMessageRepository;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
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
    private final String model;
    private final String apiKey;

    private static final String systemPrompt = """
            You are an expert technical assistant.
            
            Always respond using clean, well-structured Markdown formatting.
            Do not break words.
            
            
            Formatting rules:
            - Use proper headings (## for sections, ### for subsections).
            - Add a blank line after headings.
            - Use bullet points or numbered lists when appropriate.
            - Keep proper spacing between words.
            - Never merge words together.
            - Do not break words across lines.
            - Use short paragraphs for readability.
            
            Engagement rules:
            - Make responses engaging and easy to read.
            - Use relevant emojis occasionally (not excessively).
            - Use clear explanations with examples where helpful.
            - Highlight important keywords using **bold** formatting.
            
            Code rules:
            - Always wrap code inside proper fenced blocks using triple backticks.
            - Specify the language in code blocks (e.g., ```java).
            - Keep code clean and properly formatted.
            
            Tone:
            - Friendly, professional, and confident.
            - Avoid overly robotic language.
            - Explain concepts clearly as if teaching a developer with 2–4 years of experience.
            """;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(GeminiProperties props) {

        this.model = props.getModel();
        this.apiKey = props.getApiKey();

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }


    @Override
    public Mono<String> generate(String prompt) {

        return webClient.post()
                .uri("/{model}:generateContent", model)
                .bodyValue(buildRequest(null, null))
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::extractText);
    }


    @Override
    public Flux<String> stream(String prompt, UserContext userContext, ChatSession chatSession) {

        String user = userContext.getUserId();
        Long sessionId = chatSession.getSessionId();
        SessionHistory.addMessage(user, sessionId, "user", prompt);

        ChatMessage chatMessage = ChatMessage.builder().
                role("user")
                .content(prompt)
                .session(chatSession).build();
        Mono.fromCallable(() -> chatMessageRepository.save(chatMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        StringBuilder aiBuffer = new StringBuilder();

        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/{model}:generateContent")
                        .queryParam("alt", "sse")
                        .queryParam("key", apiKey)
                        .build(model)
                )
                .bodyValue(buildRequest(SessionHistory.getHistory(user, sessionId), userContext.getUserName()))
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(chunk -> { // Use flatMap to break chunks into words
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

                        // Split by "space" but keep the space using lookbehind regex
                        // This ensures the typing effect happens word-by-word
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
                // Now the delay applies to every individual word
                .delayElements(Duration.ofMillis(50))
                .doOnComplete(() -> {

                    SessionHistory.addMessage(user, sessionId, "assistant", aiBuffer.toString());

                    ChatMessage aiMsg = ChatMessage.builder()
                            .role("assistant")
                            .content(aiBuffer.toString())
                            .session(chatSession)
                            .build();

                    Mono.fromCallable(() -> chatMessageRepository.save(aiMsg))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                });

    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    private Map<String, Object> buildRequest(Deque<ObjectNode> history, String username) {

        String userSystem = systemPrompt + String.format(" - User name is %s", username);
        // Convert rest to Gemini format
        List<Map<String, Object>> contents = history.stream()
                .map(msg -> Map.of(
                        "role", msg.get("role").asString().equals("assistant") ? "model" : "user",
                        "parts", List.of(
                                Map.of("text", msg.get("content").asString())
                        )
                ))
                .toList();

        return Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(
                                Map.of("text", userSystem)
                        )
                ),
                "contents", contents
        );
    }


    private String extractText(Map response) {
        return response.toString();
    }
}
