package com.enterprise.kb.api.dto;

import java.time.LocalDateTime;

/**
 * 历史会话列表条目（3.15 补齐，GET /api/v1/sessions）
 */
public record SessionItem(String id, String title, Integer messageCount, LocalDateTime updatedAt) {
}
