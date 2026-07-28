package com.enterprise.kb.domain.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表
 */
@Data
@Entity
@Table(name = "kb_message")
public class KbMessage {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "role", length = 20, nullable = false)
    private String role;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "citations", columnDefinition = "JSONB")
    private String citations;

    @Column(name = "token_usage", columnDefinition = "JSONB")
    private String tokenUsage;

    @Column(name = "metadata", columnDefinition = "JSONB")
    private String metadata = "{}";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
