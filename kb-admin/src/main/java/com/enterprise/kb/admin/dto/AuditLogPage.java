package com.enterprise.kb.admin.dto;

import java.util.List;

/**
 * 审计日志分页视图（Phase 4 簇④ 4.7）。
 *
 * <p>不复用 Spring Data Page 序列化——自定义轻量载荷，规避 Jackson 3 对
 * PageImpl 的序列化形态漂移。
 */
public record AuditLogPage(
    List<AuditLogView> items,
    long total,
    int page,
    int size) {
}
