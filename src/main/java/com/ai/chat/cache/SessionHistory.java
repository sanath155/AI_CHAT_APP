package com.ai.chat.cache;

import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.entities.ChatMessage;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.repositories.ChatMessageRepository;
import com.ai.chat.repositories.ChatSessionRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionHistory {

    private static ChatSessionRepository chatSessionRepository = null;

    private static final Map<String, ChatSession> CHAT_SESSION_MAP = new ConcurrentHashMap<>();

    private static final Map<String, Deque<ObjectNode>> STORE =
            new ConcurrentHashMap<>();

    private static final int MAX_MESSAGES = 20;
    private static final ObjectMapper mapper = new ObjectMapper();

    public SessionHistory(ChatSessionRepository chatSessionRepository) {
        SessionHistory.chatSessionRepository = chatSessionRepository;
    }


    private static String key(String userId, Long sessionId) {
        return userId + ":" + sessionId;
    }

    public static void getOrLoadHistory(
            String userId,
            Long sessionId,
            ChatMessageRepository repository
    ) {

        String key = key(userId, sessionId);

        STORE.computeIfAbsent(key, k -> {

            Deque<ObjectNode> deque = new ArrayDeque<>();

            List<ChatMessage> dbMessages =
                    repository.findBySession_SessionIdOrderByCreatedDateAsc(sessionId);

            // Take only last N messages (sliding window)
            int start = Math.max(0, dbMessages.size() - MAX_MESSAGES);

            for (int i = start; i < dbMessages.size(); i++) {

                ChatMessage msg = dbMessages.get(i);

                ObjectNode node = mapper.createObjectNode();
                node.put("role", msg.getRole());
                node.put("content", msg.getContent());

                deque.addLast(node);
            }

            return deque;
        });
    }

    public static void addMessage(
            String userId,
            Long sessionId,
            String role,
            String content
    ) {

        Deque<ObjectNode> history =
                STORE.get(key(userId, sessionId));

        if (history == null) return;

        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);

        history.addLast(node);

        while (history.size() > MAX_MESSAGES) {
            history.pollFirst();
        }
    }

    public static Deque<ObjectNode> getHistory(String userId, Long sessionId) {
        return STORE.get(key(userId, sessionId));
    }

    public static ChatSession getOrLoadSession(
            String userId,
            Long sessionId
    ) {

        String key = key(userId, sessionId);

        return CHAT_SESSION_MAP.computeIfAbsent(key, k ->
                chatSessionRepository
                        .findTopByUserIdAndSessionId(userId, sessionId)
                        .orElseThrow()
        );
    }

    public static void loadChatMessageCache(Long sessionId, String userId, List<ChatMessageDto> chatMessageDtoList) {
        String key = key(userId, sessionId);
        STORE.computeIfAbsent(key, k -> {
            Deque<ObjectNode> deque = new ArrayDeque<>();
            for (ChatMessageDto chatMessageDto : chatMessageDtoList) {
                ObjectNode node = mapper.createObjectNode();
                node.put("role", chatMessageDto.getRole());
                node.put("content", chatMessageDto.getContent());
                deque.addLast(node);
            }
            return deque;
        });
    }

    public static void removeHistory(String userId, Long sessionId) {
        String key = key(userId, sessionId);
        STORE.remove(key);
    }
}

