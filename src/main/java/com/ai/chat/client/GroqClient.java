package com.ai.chat.client;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.config.GroqProperties;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@Component
public class GroqClient implements LLMClient {

    private final WebClient webClient;
    private final GroqProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ChatMessageRepository chatMessageRepository;

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

    public GroqClient(GroqProperties properties) {
        this.properties = properties;

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);

        this.webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private Map<String, Object> buildRequest(Deque<ObjectNode> history, String username) {
        String userSystem = systemPrompt + String.format(" - User name is %s", username);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", userSystem
        ));
        history.forEach(node ->
                messages.add(Map.of(
                        "role", node.get("role").asString(),
                        "content", node.get("content").asString()
                ))
        );
        return Map.of(
                "model", properties.getModel(),
                "stream", true,
                "messages", messages,
                "temperature", 0.2,
                "top_p", 0.9
        );
    }

    @Override
    public Mono<String> generate(String prompt) {
        return null;
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

        ChatMessage chatMessage = ChatMessage.builder().
                role("user")
                .content(prompt)
                .session(chatSession).build();
        Mono.fromCallable(() -> chatMessageRepository.save(chatMessage))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();


        StringBuilder aiResponseBuffer = new StringBuilder();

        return webClient.post()
                .uri("/chat/completions")
                .bodyValue(buildRequest(SessionHistory.getHistory(user, sessionId), userContext.getUserName()))
                .retrieve()
                .bodyToFlux(String.class)
                .map(chunk -> {
                    if (chunk.contains("[DONE]")) return "{\"done\":true}";
                    try {
                        int start = chunk.indexOf("{");
                        if (start == -1) return "";

                        JsonNode root = mapper.readTree(chunk.substring(start));
                        String content = root.path("choices").get(0).path("delta").path("content").asString("");

                        aiResponseBuffer.append(content);

                        // Wrap the content in a simple JSON to protect spaces
                        ObjectNode response = mapper.createObjectNode();
                        response.put("content", content);
                        return response.toString();
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(text -> !text.isEmpty())
                .delayElements(Duration.ofMillis(60))
                .doOnComplete(() -> {
                    SessionHistory.addMessage(user, sessionId, "assistant", aiResponseBuffer.toString());

                    ChatMessage aiMsg = ChatMessage.builder()
                            .role("assistant")
                            .content(aiResponseBuffer.toString())
                            .session(chatSession)
                            .build();

                    Mono.fromCallable(() -> chatMessageRepository.save(aiMsg))
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();
                });
    }

}
