package com.ai.chat.service;

import com.ai.chat.dto.UserContext;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

public interface ChatService {

    Flux<String> streamChat(String provider, String message, UserContext userContext, Long sessionId);

    ResponseEntity<?> loadSessions(String userId);

    ResponseEntity<?> getMessages(Long sessionId, String userId);

    ResponseEntity<?> createNewSession(String userId);
}
