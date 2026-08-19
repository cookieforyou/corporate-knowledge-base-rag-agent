package com.enterprise.kb.admin.dto;

import java.time.LocalDateTime;

/**
 * 词项编辑视图（v2.53 词表 DB 单轨，GET /rules/{id} 专用）——
 * 与列表视图 {@link GuardrailRuleView}（指纹契约、value 不出服务）分层：
 * 本视图携带 <b>valueB64 编码态</b>供编辑弹窗解码预填（用户定案：
 * 编辑回显明文经前端浏览器内解码，传输链路恒编码态）。
 *
 * @param id        词项业务 id
 * @param side      侧别
 * @param family    族系枚举名
 * @param lang      语种标注
 * @param type      KEYWORD | REGEX
 * @param action    BLOCK | FLAG
 * @param enabled   启用态
 * @param valueB64  词值 Base64 编码态
 * @param sha256    SHA-256 指纹前 12 位（与列表视图同口径）
 * @param charLen   解码后字符长度
 * @param origin    词项来源（MIGRATION | API）
 * @param createdBy 创建者（JWT 租户身份）
 * @param updatedBy 最近修改者
 * @param createdAt 创建时间
 * @param updatedAt 最近修改时间
 */
public record GuardrailRuleEditView(
    String id,
    String side,
    String family,
    String lang,
    String type,
    String action,
    boolean enabled,
    String valueB64,
    String sha256,
    int charLen,
    String origin,
    String createdBy,
    String updatedBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
