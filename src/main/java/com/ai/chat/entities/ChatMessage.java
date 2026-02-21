package com.ai.chat.entities;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

@Builder
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "t_chat_message", schema = "ai_chat")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "role")
    private String role; // system, user, assistant

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @JsonFormat(pattern = "dd-MM-yyyy HH:mm:ss", timezone = "IST")
    @Column(name = "created_date")
    private Timestamp createdDate;

    @ManyToOne
    @JoinColumn(name = "session_id")
    private ChatSession session;
}
