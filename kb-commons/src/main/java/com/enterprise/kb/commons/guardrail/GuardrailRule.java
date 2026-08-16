package com.enterprise.kb.commons.guardrail;

import java.util.regex.Pattern;

/**
 * 护栏词项模型（安全簇① A1，设计 12.7 词表工程）。
 *
 * <p><b>编码态纪律</b>（第七节条 2）：词项 {@code value} 的<b>静态存储形态永远是
 * Base64 编码态</b>（结构化规则文件内），本 record 持有的 {@code value} 是加载层解码后的
 * 运行时形态——解码仅在 {@link GuardrailRulesLoader} 发生，任何手写代码不得内嵌字面值。
 *
 * @param id       词项唯一标识（词表运营 / 审计 / 清单引用锚点）
 * @param family   族系/分类名（中性）：注入侧取 {@link GuardrailFamily} 七分法，
 *                 输出侧取输出分类名（业务保密/合规敏感/竞品对比三分类）；
 *                 两套族系语义不同，见 {@link GuardrailFamily} 类注
 * @param lang     语种标注（zh / en / ja / …，未标注为空串）
 * @param type     匹配类型 KEYWORD | REGEX
 * @param value    运行时明文：KEYWORD 为已小写化词干；REGEX 为模式源文
 * @param action   命中动作 BLOCK | FLAG
 * @param enabled  停用开关（词表运营期热停用单条词项的载体）
 * @param compiled REGEX 预编译模式（KEYWORD 为 null）
 */
public record GuardrailRule(
        String id,
        String family,
        String lang,
        RuleType type,
        String value,
        RuleAction action,
        boolean enabled,
        Pattern compiled) {

    /**
     * 匹配判定（仅 enabled 词项参与）：调用方须先以
     * {@code TextSanitizer#normalize} 构造归一化检测视图传入。
     * KEYWORD 大小写不敏感子串匹配；REGEX 预编译模式 find（大小写不敏感编译）。
     */
    public boolean matches(String normalizedText) {
        if (!enabled || normalizedText == null || normalizedText.isEmpty()) {
            return false;
        }
        if (type == RuleType.REGEX) {
            return compiled != null && compiled.matcher(normalizedText).find();
        }
        return normalizedText.toLowerCase().contains(value);
    }
}
