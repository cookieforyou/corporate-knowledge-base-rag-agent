package com.enterprise.kb.admin.dto;

import java.util.List;

/**
 * 护栏词表分页视图——范式对齐 {@link AuditLogPage}：自定义轻量载荷，
 * 规避 Jackson 3 对 Spring Data PageImpl 的序列化形态漂移。
 *
 * <p>分页施加于注册表活快照的过滤结果（内存切片）——读路径不触 DB，
 * source=file/db 两形态同语义，F1 热重载后即时一致。
 */
public record GuardrailRulePage(
    List<GuardrailRuleView> items,
    long total,
    int page,
    int size) {
}
