package com.ai.chat.repositories;


import com.ai.chat.client.LLMClient;
import com.ai.chat.entities.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findTopByUserIdAndSessionId(String userId, Long sessionId);

    List<ChatSession> findByUserIdOrderByCreatedDateDesc(String userId);
}
