package com.enterprise.kb.admin.dto;

/**
 * 词项更新请求（v2.53 词表 DB 单轨）——全字段可选，null = 保持原值；
 * valueB64 非 null 时按创建同口径校验并重算指纹/去重。
 *
 * @param family   族系枚举名（null = 保持）
 * @param valueB64 词值 Base64 编码态（null = 保持）
 * @param lang     语种标注（null = 保持；空串 = 清空）
 * @param type     KEYWORD | REGEX（null = 保持）
 * @param action   BLOCK | FLAG（null = 保持）
 * @param enabled  启用态（null = 保持）
 */
public record GuardrailRuleUpdateRequest(
    String family,
    String valueB64,
    String lang,
    String type,
    String action,
    Boolean enabled
) {
}
