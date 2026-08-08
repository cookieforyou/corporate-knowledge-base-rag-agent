package com.enterprise.kb.api.dto;

import java.util.List;

/**
 * 用户反馈提交请求（3.17）
 *
 * @param messageId      被评价的助手消息 ID（SSE DONE 帧 / 同步 /chat 响应送达，必填）
 * @param traceId        本轮问答 trace ID（关联 kb_audit_log 回填 feedback，可选）
 * @param rating         POSITIVE | NEGATIVE（大小写不敏感）
 * @param expectedAnswer 期望回答（点踩时用户补充，可选）
 * @param tags           反馈标签（可选，JSONB 落库）
 */
public record FeedbackRequest(
    String messageId,
    String traceId,
    String rating,
    String expectedAnswer,
    List<String> tags) {}
