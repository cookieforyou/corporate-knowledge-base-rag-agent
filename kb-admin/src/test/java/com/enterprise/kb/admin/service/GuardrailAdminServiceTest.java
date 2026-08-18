package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.DrillResult;
import com.enterprise.kb.admin.dto.GuardrailRuleView;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 护栏词表运维服务测试（安全簇⑥ F2）——列表过滤 / 演练命中 / 元数据视图纪律。
 * 词表经测试资源占位词表（guardrail-test/admin-*-rules.yml，无攻击语义）。
 */
class GuardrailAdminServiceTest {

    private static final String INJECTION_RULES = "classpath:guardrail-test/admin-injection-rules.yml";
    private static final String OUTPUT_RULES = "classpath:guardrail-test/admin-output-rules.yml";

    private GuardrailAdminService service;

    @BeforeEach
    void setUp() {
        service = new GuardrailAdminService(
            new GuardrailRulesRegistry(INJECTION_RULES, "", OUTPUT_RULES, ""));
    }

    @Test
    void listRulesReturnsBothSidesAsMetadataViews() {
        List<GuardrailRuleView> views = service.listRules(null, null, null, null, null, null);

        // 注入侧 3 条（含停用条——列表为枚举视图）+ 输出侧 1 条
        assertThat(views).hasSize(4);
        assertThat(views).extracting(GuardrailRuleView::side)
            .containsExactly("injection", "injection", "injection", "output");
        // value 不回显：视图仅指纹（12 位）+ 长度轮廓（record 结构无 value 面）
        assertThat(views).allSatisfy(view -> {
            assertThat(view.sha256()).hasSize(12);
            assertThat(view.charLen()).isPositive();
        });
    }

    @Test
    void listRulesFiltersBySideFamilyActionEnabledAndType() {
        assertThat(service.listRules("injection", null, null, null, null, null)).hasSize(3);
        assertThat(service.listRules("output", null, null, null, null, null)).hasSize(1);
        assertThat(service.listRules(null, "jailbreak", null, null, null, null))
            .extracting(GuardrailRuleView::id)
            .containsExactly("admin-probe-inj-regex");
        assertThat(service.listRules(null, null, null, "flag", null, null)).hasSize(2);
        assertThat(service.listRules(null, null, null, null, true, null)).hasSize(3);
        assertThat(service.listRules(null, null, null, null, null, "REGEX")).hasSize(1);
    }

    @Test
    void drillMatchesRuntimeSemanticsAcrossBothSides() {
        DrillResult result = service.drill("文本含 drill-probe-inj 与 drill-probe-out 占位词");

        // 注入侧 KEYWORD 命中（停用同值词项不参与——enabled 过滤与运行时同口径）
        assertThat(result.injectionMatches())
            .extracting(GuardrailRuleView::id)
            .containsExactly("admin-probe-inj-kw");
        assertThat(result.outputMatches())
            .extracting(GuardrailRuleView::id)
            .containsExactly("admin-probe-out-kw");
    }

    @Test
    void drillRegexTrackHitsViaNormalizedView() {
        DrillResult result = service.drill("前缀 drill-probe-regex-abc 后缀");

        assertThat(result.injectionMatches())
            .extracting(GuardrailRuleView::id)
            .containsExactly("admin-probe-inj-regex");
        assertThat(result.injectionMatches().get(0).action()).isEqualTo("FLAG");
    }

    @Test
    void drillCleanTextReturnsEmptyMatches() {
        DrillResult result = service.drill("什么是增值税发票？");

        assertThat(result.injectionMatches()).isEmpty();
        assertThat(result.outputMatches()).isEmpty();
    }

    @Test
    void fingerprintIsStableAndSameValueSharesIdentityOutline() {
        List<GuardrailRuleView> views = service.listRules("injection", null, null, null, null, null);

        // 同值词项（首条与停用条 value 相同）→ 指纹与长度一致（跨通道比对锚点）
        assertThat(views.get(2).sha256()).isEqualTo(views.get(0).sha256());
        assertThat(views.get(2).charLen()).isEqualTo(views.get(0).charLen());
        // 不同值词项指纹必不同
        assertThat(views.get(1).sha256()).isNotEqualTo(views.get(0).sha256());
    }
}
