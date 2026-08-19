package com.enterprise.kb.commons.guardrail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表导出器 round-trip 测试（v2.53 DB 单轨）：运行时快照 → YAML（value 重编码）
 * → {@link GuardrailRulesLoader} 回载同一性。占位词纪律：零字面攻击词。
 */
class GuardrailRulesExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesRuleSemantics() throws Exception {
        List<GuardrailRule> rules = List.of(
            new GuardrailRule("probe-exp-1", "UNCLASSIFIED", "zh", RuleType.KEYWORD,
                "probe-word-a", RuleAction.FLAG, true, null),
            new GuardrailRule("probe-exp-2", "JAILBREAK", "", RuleType.REGEX,
                "probe-regex-.*", RuleAction.BLOCK, false,
                Pattern.compile("probe-regex-.*", Pattern.CASE_INSENSITIVE)));

        Path file = tempDir.resolve("rules.yml");
        Files.writeString(file, GuardrailRulesExporter.toYaml(rules, "injection"));

        List<GuardrailRule> reloaded = GuardrailRulesLoader.loadInjectionRules(
            "file:" + file.toAbsolutePath(), "");

        assertThat(reloaded).hasSize(2);
        GuardrailRule kw = reloaded.get(0);
        assertThat(kw.id()).isEqualTo("probe-exp-1");
        assertThat(kw.value()).isEqualTo("probe-word-a");
        assertThat(kw.action()).isEqualTo(RuleAction.FLAG);
        assertThat(kw.enabled()).isTrue();
        assertThat(kw.compiled()).isNull();
        GuardrailRule regex = reloaded.get(1);
        assertThat(regex.id()).isEqualTo("probe-exp-2");
        assertThat(regex.value()).isEqualTo("probe-regex-.*");
        assertThat(regex.action()).isEqualTo(RuleAction.BLOCK);
        assertThat(regex.enabled()).isFalse();
        assertThat(regex.compiled()).isNotNull();
    }

    @Test
    void exportedYamlStoresEncodedValuesOnly() throws Exception {
        List<GuardrailRule> rules = List.of(
            new GuardrailRule("probe-exp-3", "UNCLASSIFIED", "zh", RuleType.KEYWORD,
                "占位词", RuleAction.BLOCK, true, null));

        String yaml = GuardrailRulesExporter.toYaml(rules, "output");

        // 导出文本只含 Base64 编码态，不承载运行时明文
        assertThat(yaml).doesNotContain("占位词");
        assertThat(yaml).contains(GuardrailRulesSupport.encodeB64("占位词"));
    }

    @Test
    void supportEncodeDecodeRoundTrip() {
        assertThat(GuardrailRulesSupport.decodeB64(GuardrailRulesSupport.encodeB64("占位词-ABC")))
            .isEqualTo("占位词-ABC");
        assertThat(GuardrailRulesSupport.sha256Hex("probe")).hasSize(64);
        assertThat(GuardrailRulesSupport.sha256Hex("probe"))
            .isEqualTo(GuardrailRulesSupport.sha256Hex("probe"));
    }
}
