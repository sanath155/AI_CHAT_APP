package com.ai.chat.service;

import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.dto.ChatSessionDto;
import com.ai.chat.dto.UserContext;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface ChatService {

    Flux<String> streamChat(String provider, String message, UserContext userContext, Long sessionId);

    Mono<List<ChatSessionDto>> loadSessions(String userId);

    Mono<List<ChatMessageDto>> getMessages(Long sessionId, String userId);

    Mono<ResponseEntity<Map<String, Long>>> createNewSession(String userId, String userName);

    Mono<ResponseEntity<String>> deleteSession(String userId, Long sessionId);
}
