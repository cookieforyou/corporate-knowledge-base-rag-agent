package com.enterprise.kb.commons.guardrail;

import com.enterprise.kb.commons.security.TextSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护栏词表结构化加载器测试（安全簇① A1/T2，设计 12.7）。
 *
 * <p>测试词表资源 {@code guardrail-test/test-rules.yml} 全部使用无害占位词
 * （Base64 编码态），不含攻击语义字面——遵循第七节敏感词交付纪律。
 *
 * <p>T2 起「内置默认」源收敛入 bundled 结构化文件（{@code builtin-*} 词项），
 * 加载器为双源合并（结构化文件 ∪ CSV 兼容）；bundled 基线规模随词表运营增长，
 * 断言以「非空 + ID 段前缀」表达，不硬编码条数。
 */
class GuardrailRulesLoaderTest {

    private static final String TEST_LOCATION = "classpath:guardrail-test/test-rules.yml";

    private GuardrailRule byId(List<GuardrailRule> rules, String id) {
        return rules.stream().filter(r -> r.id().equals(id)).findFirst()
            .orElseThrow(() -> new AssertionError("词项缺失: " + id));
    }

    // ── 双源合并与 bundled 基线 ──

    @Test
    void emptyConfigLoadsBundledBaselineWordlist() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(null, "");

        // bundled 结构化文件随 jar 发布即基线词表（T2 字面词表迁入；T3 增 REGEX 轨）
        assertThat(rules).isNotEmpty();
        assertThat(rules).allMatch(GuardrailRule::enabled);
        assertThat(rules).anyMatch(r -> r.id().startsWith("builtin-inj-"));
        // T3 形态：KEYWORD + REGEX 双型、BLOCK + FLAG 双档并存
        assertThat(rules).anyMatch(r -> r.type() == RuleType.KEYWORD);
        assertThat(rules).anyMatch(r -> r.type() == RuleType.REGEX && r.compiled() != null);
        assertThat(rules).anyMatch(r -> r.action() == RuleAction.BLOCK);
        assertThat(rules).anyMatch(r -> r.action() == RuleAction.FLAG);
    }

    @Test
    void externalLocationReplacesBundledDefault() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(TEST_LOCATION, "");

        // location 覆盖 = 替换缺省文件来源：仅测试词表词项，bundled 基线不叠加
        assertThat(byId(rules, "test-kw-01").value()).isEqualTo("测试关键词甲");
        assertThat(byId(rules, "test-kw-01").family()).isEqualTo("INSTRUCTION_OVERRIDE");
        assertThat(rules).noneMatch(r -> r.id().startsWith("builtin-inj-"));
    }

    @Test
    void csvCompatMergesWithoutReplacingBaseline() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules(null, "自定义测试词, 另一个词");

        // v2.40 语义：CSV「并入合并」——bundled 基线仍生效
        assertThat(rules).anyMatch(r -> r.id().startsWith("builtin-inj-"));
        GuardrailRule legacy = byId(rules, "legacy-csv-0");
        assertThat(legacy.value()).isEqualTo("自定义测试词");
        assertThat(legacy.type()).isEqualTo(RuleType.KEYWORD);
        assertThat(legacy.action()).isEqualTo(RuleAction.BLOCK);
        assertThat(byId(rules, "legacy-csv-1").value()).isEqualTo("另一个词");
    }

    @Test
    void outputBaselineLoadsFromBundledFileAndMergesCsv() {
        // v2.43/T6：T4 通道带外内容已注入（T5 空形态锚点退役）——规模随词表运营增长
        // 不硬编码条数，钉结构不变量：族系 ∈ 输出三分类 ∪ UNCLASSIFIED、REGEX 预编译
        List<GuardrailRule> baseline = GuardrailRulesLoader.loadOutputRules(null, "");
        assertThat(baseline).isNotEmpty();
        Set<String> outputFamilies = Arrays.stream(OutputFamily.values())
            .map(Enum::name).collect(Collectors.toSet());
        outputFamilies.add("UNCLASSIFIED");
        assertThat(baseline).allSatisfy(r -> {
            assertThat(outputFamilies).as("输出词项 %s 族系须为三分类或 UNCLASSIFIED", r.id())
                .contains(r.family());
            if (r.type() == RuleType.REGEX) {
                assertThat(r.compiled()).as("REGEX 词项 %s 应预编译", r.id()).isNotNull();
            }
        });

        List<GuardrailRule> merged = GuardrailRulesLoader.loadOutputRules(null, "输出测试词");
        assertThat(byId(merged, "legacy-csv-0").value()).isEqualTo("输出测试词");
    }

    @Test
    void missingExternalLocationFallsBackToBundledDefault() {
        List<GuardrailRule> rules =
            GuardrailRulesLoader.loadInjectionRules("classpath:guardrail-test/no-such-file.yml", "");

        // 外部覆盖资源缺失 → warn 回落内置缺省文件，基线词表不静默失效（T2 fail-safe 定案）
        assertThat(rules).isNotEmpty();
        assertThat(rules).anyMatch(r -> r.id().startsWith("builtin-inj-"));
    }

    // ── bundled 基线 ID 段 × 型/档分布钉死（T7 E2E 回归防护）──

    /**
     * builtin-inj ID 段的型/档分布是 FLAG 观察语义（T7）的运行时前提——
     * 任一段被误改动作档（如 FLAG 观察词项被误钉 BLOCK，或 BLOCK 结构模式
     * 被误降 FLAG）都会改变拦截/观察行为，须在词表变更时显式过测试。
     * 段分布：01..14 KEYWORD/BLOCK（干词轨）· 15..20 REGEX/BLOCK ·
     * 21..30 REGEX/FLAG（T3 结构模式轨，FLAG 观察档）。
     */
    @Test
    void bundledBaselineIdSegmentsPinTypeAndActionTiers() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.id().startsWith("builtin-inj-"))
            .toList();
        assertThat(rules).hasSize(30);
        for (GuardrailRule rule : rules) {
            int seq = Integer.parseInt(rule.id().substring("builtin-inj-".length()));
            if (seq <= 14) {
                assertThat(rule.type()).as(rule.id() + " 干词轨须为 KEYWORD").isEqualTo(RuleType.KEYWORD);
                assertThat(rule.action()).as(rule.id() + " 干词轨须为 BLOCK").isEqualTo(RuleAction.BLOCK);
            } else if (seq <= 20) {
                assertThat(rule.type()).as(rule.id() + " 结构模式轨须为 REGEX").isEqualTo(RuleType.REGEX);
                assertThat(rule.action()).as(rule.id() + " 结构模式轨 BLOCK 段不得降 FLAG").isEqualTo(RuleAction.BLOCK);
            } else {
                assertThat(rule.type()).as(rule.id() + " 结构模式轨须为 REGEX").isEqualTo(RuleType.REGEX);
                assertThat(rule.action()).as(rule.id() + " FLAG 观察段不得误钉 BLOCK（误伤铁律）").isEqualTo(RuleAction.FLAG);
            }
        }
        // FLAG 观察段族系须为可归类的注入七分法成员（指标 family 标签低基数前提）
        assertThat(rules).filteredOn(r -> r.action() == RuleAction.FLAG)
            .allSatisfy(r -> assertThat(GuardrailFamily.valueOf(r.family())).isNotNull());
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
