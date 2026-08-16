package com.enterprise.kb.commons.guardrail;

import com.enterprise.kb.commons.security.TextSanitizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护栏词表结构化加载器测试（安全簇① A1，设计 12.7）。
 *
 * <p>测试词表资源 {@code guardrail-test/test-rules.yml} 全部使用无害占位词
 * （Base64 编码态），不含攻击语义字面——遵循第七节敏感词交付纪律。
 */
class GuardrailRulesLoaderTest {

    private static final String TEST_LOCATION = "classpath:guardrail-test/test-rules.yml";

    private GuardrailRule byId(List<GuardrailRule> rules, String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("词项缺失: " + id));
    }

    // ── 三源合并与内置默认 ──

    @Test
    void emptyConfigFallsBackToBuiltinDefaults() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(null, "");

        // 结构化文件缺省（默认 classpath 为空 rules）+ 无 CSV → 仅剩内置默认
        assertThat(rules).hasSize(TextSanitizer.DEFAULT_INJECTION_KEYWORDS.size());
        assertThat(rules).allMatch(r -> r.action() == RuleAction.BLOCK && r.enabled());
        assertThat(rules.get(0).id()).startsWith("builtin-inj-");
    }

    @Test
    void structuredFileMergesWithBuiltinDefaults() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");

        // 内置默认仍在（合并而非替换）+ 结构化词项并入
        assertThat(rules).anyMatch(r -> r.id().startsWith("builtin-inj-"));
        assertThat(byId(rules, "test-kw-01").value()).isEqualTo("测试关键词甲");
        assertThat(byId(rules, "test-kw-01").family()).isEqualTo("INSTRUCTION_OVERRIDE");
    }

    @Test
    void csvCompatMergesWithoutReplacingDefaults() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(null, "自定义测试词, 另一个词");

        // v2.40 语义演进：CSV 由「整体替换」转「并入合并」——内置默认仍生效
        assertThat(rules).anyMatch(r -> r.id().startsWith("builtin-inj-"));
        GuardrailRule legacy = byId(rules, "legacy-csv-0");
        assertThat(legacy.value()).isEqualTo("自定义测试词");
        assertThat(legacy.type()).isEqualTo(RuleType.KEYWORD);
        assertThat(legacy.action()).isEqualTo(RuleAction.BLOCK);
        assertThat(byId(rules, "legacy-csv-1").value()).isEqualTo("另一个词");
    }

    @Test
    void outputRulesHaveNoBuiltinDefaults() {
        assertThat(GuardrailRulesLoader.loadOutputRules(null, "")).isEmpty();

        List<GuardrailRule> fromCsv = GuardrailRulesLoader.loadOutputRules(null, "输出测试词");
        assertThat(byId(fromCsv, "legacy-csv-0").value()).isEqualTo("输出测试词");
    }

    // ── 词项五态解析 ──

    @Test
    void decodesKeywordAndLowercases() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");
        assertThat(byId(rules, "test-kw-01").type()).isEqualTo(RuleType.KEYWORD);
        assertThat(byId(rules, "test-kw-01").compiled()).isNull();
    }

    @Test
    void compilesRegexCaseInsensitive() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");
        GuardrailRule regex = byId(rules, "test-regex-01");

        assertThat(regex.type()).isEqualTo(RuleType.REGEX);
        assertThat(regex.action()).isEqualTo(RuleAction.FLAG);
        assertThat(regex.compiled()).isNotNull();
    }

    @Test
    void malformedBase64EntryIsSkipped() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");
        assertThat(rules).noneMatch(r -> r.id().equals("test-bad-b64"));
    }

    @Test
    void missingLocationYieldsEmptyStructuredSource() {
        List<GuardrailRule> rules =
            GuardrailRulesLoader.loadInjectionRules("classpath:guardrail-test/no-such-file.yml", "");
        // 资源缺失 → 该源视为空，内置默认兜底
        assertThat(rules).hasSize(TextSanitizer.DEFAULT_INJECTION_KEYWORDS.size());
    }

    // ── 匹配联动（TextSanitizer.matchRules）──

    @Test
    void keywordRuleMatchesCaseInsensitiveOnNormalizedView() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");

        List<GuardrailRule> matched =
            TextSanitizer.matchRules(TextSanitizer.normalize("请包含测试关键词甲的内容"), rules);
        assertThat(matched).extracting(GuardrailRule::id).contains("test-kw-01");
    }

    @Test
    void regexRuleMatchesStructuredPattern() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");

        List<GuardrailRule> matched =
            TextSanitizer.matchRules(TextSanitizer.normalize("这是测试任意一种模式"), rules);
        assertThat(matched).extracting(GuardrailRule::id).contains("test-regex-01");
    }

    @Test
    void disabledRuleNeverMatches() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");

        List<GuardrailRule> matched =
            TextSanitizer.matchRules(TextSanitizer.normalize("这里出现占位关键词乙了"), rules);
        assertThat(matched).extracting(GuardrailRule::id).doesNotContain("test-kw-disabled");
    }
}
