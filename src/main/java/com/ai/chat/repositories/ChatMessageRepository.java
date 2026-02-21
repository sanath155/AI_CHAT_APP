package com.ai.chat.repositories;


import com.ai.chat.entities.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop20BySessionOrderByCreatedDateDesc(Long session);

    List<ChatMessage> findBySession_SessionIdOrderByCreatedDateAsc(Long sessionId);
}