package com.enterprise.kb.admin.dto;

/**
 * 护栏词项运维视图（安全簇⑥ F2）——元数据形态，<b>value 明文不回显</b>
 * （第七节敏感词交付纪律：运营面只暴露词项身份与指纹，字面词值留在编码态
 * 词表文件内，经带外通道维护）。
 *
 * @param id      词项唯一标识（运营/清单引用锚点）
 * @param side    侧别：injection | output
 * @param family  族系枚举名（注入侧七分法 / 输出侧三分类，各含 UNCLASSIFIED）
 * @param lang    语种标记（zh/en/...，可为空串）
 * @param type    匹配类型：KEYWORD | REGEX
 * @param action  动作档：BLOCK | FLAG
 * @param enabled 启用态
 * @param sha256  value 的 SHA-256 指纹前 12 位（跨通道比对锚点，不反推原文）
 * @param charLen value 字符长度（规模感知，与指纹共同构成词项身份轮廓）
 */
public record GuardrailRuleView(
    String id,
    String side,
    String family,
    String lang,
    String type,
    String action,
    boolean enabled,
    String sha256,
    int charLen) {
}
