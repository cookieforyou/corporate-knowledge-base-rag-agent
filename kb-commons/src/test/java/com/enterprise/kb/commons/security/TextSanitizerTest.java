package com.enterprise.kb.commons.security;

import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本安全消毒组件测试（簇② B1）——归一化防绕过（S1）+ 词表匹配
 *
 * <p>PII 掩码测试随安全簇③ C2 迁至 {@code pii.PiiRecognizerRegistryTest}
 * （能力迁识别器注册表，本类不再持有 PII 正则）。
 *
 * <p>注入侧断言全部程序化构造：攻击形态（全角/零宽变体）由 bundled 基线词表的
 * 词项值在运行时变换生成，测试源码不落字面载荷（第七节敏感词交付纪律）。
 */
class TextSanitizerTest {

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

    /** 零宽拆词：词中插入 ZWSP（模拟 G2 零宽绕过形态；以码点显式构造防不可见字面） */
    private static String splitByZeroWidth(String text) {
        int mid = Math.max(1, text.length() / 2);
        return text.substring(0, mid) + (char) 0x200B + text.substring(mid);
    }

    private static boolean hitsBundled(String normalizedText) {
        return !TextSanitizer.matchRules(normalizedText,
            GuardrailRulesLoader.loadInjectionRules("", "")).isEmpty();
    }

    // ── 归一化（S1：G2 编码绕过防御）──

    @Test
    void normalizesFullWidthInjectionToAscii() {
        // 基线词项值全角化 → NFKC 还原为原值（半角）
        GuardrailRule rule = bundledKeyword("en");
        String fullWidth = toFullWidth(rule.value());

        String normalized = TextSanitizer.normalize(fullWidth);

        assertThat(normalized).isEqualTo(rule.value());
        assertThat(hitsBundled(normalized))
            .as("词项 %s 的全角变体归一后应命中", rule.id()).isTrue();
    }

    /** bundled 基线词表逐条自洽：每条中文干词带语境包裹归一后必命中；裸领域词不误伤（v2.38 误伤铁律回归） */
    @Test
    void chineseInstructionVariantsHitWordlist() {
        List<GuardrailRule> zhKeywords = GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.type() == RuleType.KEYWORD
                && r.enabled() && "zh".equals(r.lang()))
            .toList();
        assertThat(zhKeywords).as("bundled 基线须含中文干词").isNotEmpty();
        for (GuardrailRule rule : zhKeywords) {
            String payload = "前缀语境" + rule.value() + "，立即执行";
            assertThat(hitsBundled(TextSanitizer.normalize(payload)))
                .as("词项 %s 的语境变体应命中", rule.id()).isTrue();
        }
        // 误伤面控制：裸领域词不入表，正常提问不拦（词表扩面时此断言守护误伤铁律）
        assertThat(hitsBundled(TextSanitizer.normalize("什么是系统提示词"))).isFalse();
    }

    @Test
    void stripsZeroWidthCharactersSplittingKeywords() {
        // 零宽字符拆词形态归一后现出原形
        GuardrailRule rule = bundledKeyword("zh");

        assertThat(TextSanitizer.normalize(splitByZeroWidth(rule.value())))
            .as("词项 %s 的零宽拆词形态应还原", rule.id())
            .isEqualTo(rule.value());
    }

    @Test
    void collapsesWhitespaceRuns() {
        assertThat(TextSanitizer.normalize("词一\t\t词二\n\n词三")).isEqualTo("词一 词二 词三");
    }

    @Test
    void normalizeIsNullSafeAndIdempotent() {
        assertThat(TextSanitizer.normalize(null)).isNull();
        assertThat(TextSanitizer.normalize("")).isEmpty();
        assertThat(TextSanitizer.normalize("abc def")).isEqualTo("abc def");
    }

    // ── PII 掩码能力已迁 pii 包（安全簇③ C2）：见 pii.PiiRecognizerRegistryTest ──

    // ── 结构化词表匹配入口 ──

    @Test
    void matchRulesIsSafeOnNullAndEmptyInputs() {
        List<GuardrailRule> rules = GuardrailRulesLoader.loadInjectionRules("", "");

        assertThat(TextSanitizer.matchRules(null, rules)).isEmpty();
        assertThat(TextSanitizer.matchRules("", rules)).isEmpty();
        assertThat(TextSanitizer.matchRules("任意文本", List.of())).isEmpty();
        assertThat(TextSanitizer.matchRules("任意文本", null)).isEmpty();
    }
}
