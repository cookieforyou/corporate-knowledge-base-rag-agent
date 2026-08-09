package com.enterprise.kb.api.dto;

import com.enterprise.kb.api.dto.AgentStreamEvent.SourceTrace;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史消息条目（3.15 补齐，GET /api/v1/sessions/{id}/messages）
 *
 * <p>{@code sources} 为归档时写入 kb_message.citations 的溯源载荷
 * （与 SSE TRACE 帧同形，[ref-N] ↔ final 序列下标对齐契约保持）；
 * 存量消息/工具轮/闲聊轮无溯源时为 null。{@code id} 即反馈定位键
 * （assistant 消息归档复用 SSE DONE 帧 messageId，kb_feedback 外键可解析）。
 */
public record HistoryMessageItem(
    String id,
    String role,
    String content,
    LocalDateTime createdAt,
    List<SourceTrace> sources,
    String traceId,
    String feedback
) {
}
