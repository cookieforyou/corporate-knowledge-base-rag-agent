package com.enterprise.kb.domain.guardrail;

import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesSupport;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB 词表源转换测试（v2.53 词表 DB 单轨）：实体 → 运行时词项口径
 * （KEYWORD 小写化 / REGEX 预编译 / 畸形 fail-fast）。占位词纪律：零字面攻击词。
 */
class DbGuardrailRulesSourceTest {

    private KbGuardrailRuleRepository repository;
    private DbGuardrailRulesSource source;

    @BeforeEach
    void setUp() {
        repository = mock(KbGuardrailRuleRepository.class);
        source = new DbGuardrailRulesSource(repository);
    }

    private static KbGuardrailRule entity(String id, String side, String type,
                                          String decodedValue, String action) {
        KbGuardrailRule entity = new KbGuardrailRule();
        entity.setId(id);
        entity.setSide(side);
        entity.setFamily("UNCLASSIFIED");
        entity.setLang("zh");
        entity.setType(type);
        entity.setValueB64(GuardrailRulesSupport.encodeB64(decodedValue));
        entity.setFingerprint(GuardrailRulesSupport.sha256Hex(decodedValue.toLowerCase()));
        entity.setAction(action);
        entity.setEnabled(true);
        return entity;
    }

    @Test
    void keywordIsLowercasedAndRegexCompiled() {
        when(repository.findBySideOrderByIdAsc("injection")).thenReturn(List.of(
            entity("db-probe-1", "injection", "KEYWORD", "PROBE Word", "FLAG"),
            entity("db-probe-2", "injection", "REGEX", "probe-.*", "BLOCK")));

        List<GuardrailRule> rules = source.loadInjectionRules();

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).value()).isEqualTo("probe word");
        assertThat(rules.get(0).type()).isEqualTo(RuleType.KEYWORD);
        assertThat(rules.get(0).action()).isEqualTo(RuleAction.FLAG);
        assertThat(rules.get(0).compiled()).isNull();
        assertThat(rules.get(1).value()).isEqualTo("probe-.*");
        assertThat(rules.get(1).compiled()).isNotNull();
    }

    @Test
    void disabledFlagIsHonored() {
        KbGuardrailRule disabled = entity("db-probe-3", "output", "KEYWORD", "probe-out", "BLOCK");
        disabled.setEnabled(false);
        when(repository.findBySideOrderByIdAsc("output")).thenReturn(List.of(disabled));

        List<GuardrailRule> rules = source.loadOutputRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).enabled()).isFalse();
    }

    @Test
    void malformedEntityFailsFastWithIdContext() {
        KbGuardrailRule bad = entity("db-probe-bad", "injection", "KEYWORD", "probe", "FLAG");
        bad.setType("NOT_A_TYPE");
        when(repository.findBySideOrderByIdAsc(anyString())).thenReturn(List.of(bad));

        assertThatThrownBy(() -> source.loadInjectionRules())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("db-probe-bad");
    }

    @Test
    void sourceMetadataForCoordinatorAndOps() {
        assertThat(source.sourceName()).isEqualTo("db");
        assertThat(source.injectionLocation()).isEmpty();
        assertThat(source.outputLocation()).isEmpty();
    }
}
