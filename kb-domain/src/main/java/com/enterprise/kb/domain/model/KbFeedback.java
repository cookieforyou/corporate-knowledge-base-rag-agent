package com.enterprise.kb.domain.model;

import com.enterprise.kb.domain.enums.FeedbackRating;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈表
 */
@Data
@Entity
@Table(name = "kb_feedback")
public class KbFeedback {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "audit_log_id")
    private Long auditLogId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", length = 10, nullable = false)
    private FeedbackRating rating;

    @Column(name = "expected_answer", columnDefinition = "TEXT")
    private String expectedAnswer;

    @Column(name = "feedback_tags", columnDefinition = "JSONB")
    private String feedbackTags;

    @Column(name = "resolved")
    private Boolean resolved = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
