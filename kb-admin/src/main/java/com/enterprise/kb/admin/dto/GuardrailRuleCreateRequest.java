package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 词项新建请求（v2.53 词表 DB 单轨）——<b>只收 valueB64 编码态</b>
 * （前端 Base64 编码后上送，第七节交付形态约束：网络/日志/审计不承载明文）。
 *
 * @param side     侧别：injection | output
 * @param family   族系枚举名（按侧别校验：注入七分法 / 输出三分类）
 * @param valueB64 词值 Base64 编码态（服务端解码校验：非空且 ≤500 字符）
 * @param lang     语种标注（可选，缺省空串）
 * @param type     KEYWORD | REGEX（缺省 KEYWORD）
 * @param action   BLOCK | FLAG（缺省 FLAG——A4 生命周期：新词默认观察档）
 * @param enabled  启用态（缺省 true）
 */
public record GuardrailRuleCreateRequest(
    @NotBlank String side,
    @NotBlank String family,
    @NotBlank String valueB64,
    String lang,
    String type,
    String action,
    Boolean enabled
) {
}
