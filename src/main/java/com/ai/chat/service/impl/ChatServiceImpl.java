package com.ai.chat.service.impl;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.client.LLMClient;
import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.dto.ChatSessionDto;
import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatMessage;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.repositories.ChatMessageRepository;
import com.ai.chat.repositories.ChatSessionRepository;
import com.ai.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService {

    private final LLMFactory factory;

    public ChatServiceImpl(LLMFactory factory) {
        this.factory = factory;
    }

    @Autowired
    ChatSessionRepository chatSessionRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    ObjectMapper mapper = new ObjectMapper();

    @Override
    public Flux<String> streamChat(String provider, String message, UserContext userContext, Long sessionId) {

        ChatSession chatSession;
        if (sessionId == null) {
            ChatSession newSession = ChatSession.builder()
                    .userId(userContext.getUserId())
                    .userName(userContext.getUserName())
                    .build();
            chatSession = chatSessionRepository.save(newSession);
        } else {
            chatSession = SessionHistory.getOrLoadSession(userContext.getUserId(), sessionId);
        }

        SessionHistory.getOrLoadHistory(
                userContext.getUserId(),
                chatSession.getSessionId(),
                chatMessageRepository
        );

        LLMClient client = factory.getClient(provider);
        return client.stream(message, userContext, chatSession);
    }

    @Override
    public ResponseEntity<?> createNewSession(String userId) {
        ChatSession session = chatSessionRepository.save(
                ChatSession.builder()
                        .userId(userId)
                        .userName("Shiva")
                        .build()
        );

        return ResponseEntity.ok(
                Map.of("sessionId", session.getSessionId())
        );
    }

    @Override
    public ResponseEntity<?> loadSessions(String userId) {
        List<ChatSessionDto> chatSessionDtoList = chatSessionRepository
                .findByUserIdOrderByCreatedDateDesc(userId)
                .stream()
                .map(session -> new ChatSessionDto(
                        session.getSessionId(),
                        session.getCreatedDate()
                ))
                .toList();
        return ResponseEntity.ok(chatSessionDtoList);
    }

    @Override
    public ResponseEntity<?> getMessages(Long sessionId, String userId) {
        Deque<ObjectNode> objectNodes = SessionHistory.getHistory(userId, sessionId);
        if (objectNodes != null && !objectNodes.isEmpty())
            return ResponseEntity.ok(new ArrayList<>(objectNodes));

        List<ChatMessageDto> chatMessageDtoList = chatMessageRepository
                .findBySession_SessionIdOrderByCreatedDateAsc(sessionId)
                .stream()
                .map(msg -> new ChatMessageDto(
                        msg.getRole(),
                        msg.getContent(),
                        msg.getCreatedDate()
                ))
                .toList();
        SessionHistory.loadChatMessageCache(sessionId, userId, chatMessageDtoList);
        return ResponseEntity.ok(chatMessageDtoList);
    }

}
