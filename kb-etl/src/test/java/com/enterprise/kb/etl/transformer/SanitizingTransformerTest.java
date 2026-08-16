package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入库安全消毒转换器测试（簇② B1）：PII 掩码 + 注入打标（不阻断）+ 开关语义
 *
 * <p>注入侧断言全部程序化构造：载荷形态由 bundled 基线词表的词项值在运行时
 * 变换生成，测试源码不落字面载荷（第七节敏感词交付纪律）。
 */
class SanitizingTransformerTest {

    private final SanitizingTransformer transformer = new SanitizingTransformer("", "", true, true);

    /** 取 bundled 基线词表一条启用 BLOCK KEYWORD 词项（运行时取值，源码零字面） */
    private static GuardrailRule bundledKeyword(String lang) {
        return GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.type() == RuleType.KEYWORD
                && r.enabled() && lang.equals(r.lang()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("bundled 基线词表缺少 " + lang + " KEYWORD 词项"));
    }

    /** 全角变体：ASCII 可打印字符映射至全角区（模拟 G2 全角绕过形态） */
    private static String toFullWidth(String ascii) {
        StringBuilder sb = new StringBuilder(ascii.length());
        for (char c : ascii.toCharArray()) {
            sb.append(c >= '!' && c <= '~' ? (char) (c + 0xFEE0) : c);
        }
        return sb.toString();
    }

    // ── PII 消毒 ──

    @Test
    void masksPiiBeforePersistence() {
        Document chunk = Document.builder()
            .text("紧急联系人张三 13812345678，邮箱 zhang.san@corp.com")
            .metadata("chunk_type", "TEXT")
            .build();

        Document result = transformer.apply(List.of(chunk)).get(0);

        assertThat(result.getText())
            .contains("1***-****-****")
            .contains("***@***.***")
            .doesNotContain("13812345678");
        // 元数据保留（mutate 语义不丢既有键）
        assertThat(result.getMetadata()).containsEntry("chunk_type", "TEXT");
    }

    // ── 注入扫描 ──

    @Test
    void injectionPayloadFlaggedButNotBlocked() {
        // 间接注入载荷：打标放行（12.4.3 S4 定案不阻断入库），文本不被修改
        GuardrailRule rule = bundledKeyword("zh");
        String payload = "正常业务段落。" + rule.value() + "。";
        Document chunk = Document.builder().text(payload).build();

        Document result = transformer.apply(List.of(chunk)).get(0);

        assertThat(result.getMetadata())
            .as("词项 %s 构造的载荷应打标", rule.id())
            .containsEntry(SanitizingTransformer.INJECTION_HIT_KEY, true);
        assertThat(result.getText()).contains(rule.value());
    }

    @Test
    void fullWidthInjectionPayloadFlaggedAfterNormalization() {
        // G2 同型防御延伸至入库侧：全角载荷归一化后命中
        GuardrailRule rule = bundledKeyword("en");
        Document chunk = Document.builder()
            .text("正文 " + toFullWidth(rule.value()))
            .build();

        Document result = transformer.apply(List.of(chunk)).get(0);

        assertThat(result.getMetadata())
            .as("词项 %s 的全角变体应打标", rule.id())
            .containsEntry(SanitizingTransformer.INJECTION_HIT_KEY, true);
    }

    @Test
    void cleanChunkPassesThroughAsSameInstance() {
        Document chunk = Document.builder().text("增值税发票税率为 13%").build();

        assertThat(transformer.apply(List.of(chunk)).get(0)).isSameAs(chunk);
    }

    // ── 开关语义 ──

    @Test
    void piiDisabledLeavesTextUntouched() {
        SanitizingTransformer off = new SanitizingTransformer("", "", false, true);
        Document chunk = Document.builder().text("电话 13812345678").build();

        Document result = off.apply(List.of(chunk)).get(0);

        assertThat(result.getText()).contains("13812345678");
    }

    @Test
    void injectionScanDisabledLeavesNoFlag() {
        SanitizingTransformer off = new SanitizingTransformer("", "", true, false);
        GuardrailRule rule = bundledKeyword("en");
        Document chunk = Document.builder().text("正文 " + rule.value()).build();

        Document result = off.apply(List.of(chunk)).get(0);

        assertThat(result.getMetadata()).doesNotContainKey(SanitizingTransformer.INJECTION_HIT_KEY);
    }

    @Test
    void configuredKeywordsMergeWithDefaultsForScan() {
        SanitizingTransformer custom = new SanitizingTransformer("", "测试注入占位词", true, true);
        Document hit = Document.builder().text("正文包含测试注入占位词的段落").build();
        GuardrailRule builtin = bundledKeyword("en");
        Document builtinHit = Document.builder().text("正文 " + builtin.value()).build();

        // 配置词命中；v2.40 双源合并——bundled 基线词并入后仍参与扫描（不再被整体替换）
        assertThat(custom.apply(List.of(hit)).get(0).getMetadata())
            .containsEntry(SanitizingTransformer.INJECTION_HIT_KEY, true);
        assertThat(custom.apply(List.of(builtinHit)).get(0).getMetadata())
            .as("词项 %s 基线词应仍参与扫描", builtin.id())
            .containsEntry(SanitizingTransformer.INJECTION_HIT_KEY, true);
    }

    @Test
    void emptyTextChunkPassesThrough() {
        Document chunk = Document.builder().text("").metadata(Map.of()).build();

        assertThat(transformer.apply(List.of(chunk)).get(0)).isSameAs(chunk);
    }
}
