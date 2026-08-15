package com.enterprise.kb.admin.dto;

import com.enterprise.kb.domain.model.KbAuditLog;

import java.time.LocalDateTime;

/**
 * 审计日志视图（Phase 4 簇④ 4.7）——运维查询端点载荷。
 *
 * <p>JSON 快照列（retrievedChunks/rerankedChunks/toolCalls/tokenUsage）原样透传
 * JSON 字符串，前端按需解析渲染（chunk_id/file_name/page_num/score 投影形态
 * 见 AuditTraceAdvisor.retrievalProjection）。
 *
 * @param feedbackExpectedAnswer 关联反馈的期望回答（kb_feedback.audit_log_id 联查，
 *                               Golden 回灌对话框预填用；无关联反馈为 null）
 */
public record AuditLogView(
    Long id,
    String traceId,
    String sessionId,
    String userId,
    String mode,
    String queryText,
    String rewrittenQuery,
    String retrievalType,
    String retrievedChunks,
    String rerankedChunks,
    String finalAnswer,
    String toolCalls,
    String modelName,
    Integer latencyMs,
    String tokenUsage,
    String status,
    String errorCode,
    String feedback,
    String rootCause,
    LocalDateTime createdAt,
    String feedbackExpectedAnswer) {

    public static AuditLogView from(KbAuditLog audit, String feedbackExpectedAnswer) {
        return new AuditLogView(
            audit.getId(), audit.getTraceId(), audit.getSessionId(), audit.getUserId(),
            audit.getMode(), audit.getQueryText(), audit.getRewrittenQuery(),
            audit.getRetrievalType(), audit.getRetrievedChunks(), audit.getRerankedChunks(),
            audit.getFinalAnswer(), audit.getToolCalls(), audit.getModelName(),
            audit.getLatencyMs(), audit.getTokenUsage(), audit.getStatus(),
            audit.getErrorCode(), audit.getFeedback(), audit.getRootCause(),
            audit.getCreatedAt(), feedbackExpectedAnswer);
    }
}
