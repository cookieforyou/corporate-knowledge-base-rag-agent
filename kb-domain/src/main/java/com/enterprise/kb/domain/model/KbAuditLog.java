package com.enterprise.kb.domain.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 审计日志表
 *
 * <p>v2.10 扩展（3.12）：mode（双链路问答模式）/ status（SUCCESS/REJECTED/ERROR）/
 * error_code（拒绝错误码）/ tool_calls（工具调用记录 JSON）。
 * <b>注意</b>：ddl-auto=validate，存量库须先执行 schema.sql 注释中的 ALTER 语句。
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

    /** 问答模式（3.19 双链路）：rag | tool */
    @Column(name = "mode", length = 10)
    private String mode;

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

    /** 工具调用记录 JSON（3.12/3.4）：RetrievalContext.ToolCall 列表投影 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "JSONB")
    private String toolCalls;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "token_usage", columnDefinition = "JSONB")
    private String tokenUsage;

    /** 请求结局（3.12）：SUCCESS / REJECTED / ERROR */
    @Column(name = "status", length = 20)
    private String status;

    /** 拒绝/失败错误码（3.12）：RATE_LIMITED / PROMPT_INJECTION / TOKEN_BUDGET_EXCEEDED 等 */
    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "feedback", length = 10)
    private String feedback;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
