package com.enterprise.kb.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 审计日志表
 */
@Data
@Entity
@Table(name = "kb_audit_log")
public class KbAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "tenant_id", length = 36)
    private String tenantId;

    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    @Column(name = "rewritten_query", columnDefinition = "TEXT")
    private String rewrittenQuery;

    @Column(name = "retrieval_type", length = 30)
    private String retrievalType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_chunks", columnDefinition = "JSONB")
    private String retrievedChunks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reranked_chunks", columnDefinition = "JSONB")
    private String rerankedChunks;

    @Column(name = "final_answer", columnDefinition = "TEXT")
    private String finalAnswer;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage", columnDefinition = "JSONB")
    private String tokenUsage;

    @Column(name = "feedback", length = 10)
    private String feedback;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
