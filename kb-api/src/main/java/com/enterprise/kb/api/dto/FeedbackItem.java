package com.enterprise.kb.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 反馈查询投影（3.17 Bad Case 查询，验收 #13）：反馈行 + 关联的原始问答文本，
 * 供运维排查直接可读（Phase 4.8 Bad Case 面板的数据基座）。
 *
 * @param auditLogId 关联审计行（经 trace_id 回填成功则非空，可进一步联查检索快照）
 */
public record FeedbackItem(
    String feedbackId,
    String messageId,
    String sessionId,
    String rating,
    String expectedAnswer,
    List<String> tags,
    Boolean resolved,
    LocalDateTime createdAt,
    Long auditLogId,
    String query,
    String answer) {}
