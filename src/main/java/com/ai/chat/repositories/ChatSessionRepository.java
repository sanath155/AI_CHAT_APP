package com.ai.chat.repositories;


import com.ai.chat.entities.ChatSession;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    Optional<ChatSession> findTopByUserIdAndSessionId(String userId, Long sessionId);

    List<ChatSession> findByUserIdOrderByCreatedDateDesc(String userId);

    @Modifying
    @Transactional
    @Query("update ChatSession cs set cs.title = :title where cs.sessionId = :sessionId")
    void updateTitle(@Param("sessionId") Long sessionId, @Param("title") String title);
}
