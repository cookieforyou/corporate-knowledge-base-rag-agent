package com.enterprise.kb.commons.guardrail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 护栏词表导出器（v2.53 词表 DB 单轨，设计 12.7）——{@link GuardrailRulesLoader}
 * 的对称物：运行时词表快照 → 与 bundled {@code guardrail/*-rules.yml} 同构的
 * YAML 文本（七字段 / value 逐条 Base64 重编码），供 DB 单轨写后导出 git 存档
 * （审计 / 回滚 / kb-eval 测量快照引用）。
 *
 * <p><b>编码态纪律</b>（第七节条 2）：导出文本内 value 恒为 Base64 编码态——
 * 存档文件可直接入 git 仓库与文档链路，不触发 AI 辅助链路误检测。
 * KEYWORD 词项导出其运行时形态（已小写化，与加载层归一化幂等一致）。
 */
public final class GuardrailRulesExporter {

    private GuardrailRulesExporter() {
    }

    /**
     * 词表快照 → YAML 文本（顶层 {@code rules:} 序列，词项保序）。
     *
     * @param rules 运行时词表（value 为解码态）
     * @param side  侧别标注（injection / output，仅入头注释）
     */
    public static String toYaml(List<GuardrailRule> rules, String side) {
        StringBuilder sb = new StringBuilder(256 + rules.size() * 96);
        sb.append("# 护栏词表存档（").append(side).append(" 侧）——词表运营 API 自动导出（v2.53 DB 单轨）\n");
        sb.append("# 词项模型与编码纪律同 classpath:guardrail/*-rules.yml；本文件为派生存档，\n");
        sb.append("# 唯一事实源为 kb_guardrail_rule 表（git commit 归档供审计 / 回滚 / eval 引用）。\n");
        sb.append("rules:\n");
        for (GuardrailRule rule : rules) {
            sb.append("- id: ").append(rule.id()).append('\n');
            sb.append("  family: ").append(rule.family()).append('\n');
            sb.append("  lang: ").append(rule.lang()).append('\n');
            sb.append("  type: ").append(rule.type().name()).append('\n');
            sb.append("  value: \"").append(encode(rule.value())).append("\"\n");
            sb.append("  action: ").append(rule.action().name()).append('\n');
            sb.append("  enabled: ").append(rule.enabled()).append('\n');
        }
        return sb.toString();
    }

    /** 运行时明文 → Base64 编码态（加载层解码的逆操作）。 */
    public static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
