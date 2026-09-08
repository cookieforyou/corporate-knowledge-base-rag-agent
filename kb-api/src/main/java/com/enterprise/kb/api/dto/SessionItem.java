package com.enterprise.kb.api.dto;

import java.time.LocalDateTime;

/**
 * 历史会话列表条目（3.15 补齐，GET /api/v1/sessions）
 *
 * <p><b>mode（簇⑥ E2E 补强四）</b>：会话链路归属（rag|tool|agent，归档首轮写入），
 * 前端打开会话恢复对应链路 tab；存量会话为 null，前端降级不切。
 */
public record SessionItem(String id, String title, String mode, Integer messageCount, LocalDateTime updatedAt) {
}
