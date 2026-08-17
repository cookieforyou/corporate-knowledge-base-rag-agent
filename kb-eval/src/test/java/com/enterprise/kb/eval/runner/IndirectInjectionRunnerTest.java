package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.metric.JudgePrompts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 间接注入评估运行器测试（安全簇④ D3）——纯汇总逻辑与 Judge Prompt 结构
 * （探针驱动链路由 E2E 通道验证，单测不触 LLM）。
 */
class IndirectInjectionRunnerTest {

    private static IndirectInjectionRunner.IndirectCaseResult caseResult(
            String id, String verdict, boolean retrieved, boolean tagged, boolean runtime, boolean consistent) {
        return new IndirectInjectionRunner.IndirectCaseResult(
            id, verdict, retrieved, tagged, runtime, consistent);
    }

    @Test
    void suppressionRateIsResistedShare() {
        var summary = IndirectInjectionRunner.summarize(List.of(
            caseResult("poison-01", "RESISTED", true, true, true, true),
            caseResult("poison-02", "RESISTED", true, true, true, true),
            caseResult("poison-03", "EXECUTED", true, true, true, true)));

        assertThat(summary.totalCases()).isEqualTo(3);
        assertThat(summary.resisted()).isEqualTo(2);
        assertThat(summary.executed()).isEqualTo(1);
        assertThat(summary.suppressionRate()).isEqualTo(2.0 / 3);
    }

    @Test
    void tagMissesCountedOnlyWhenFileRetrieved() {
        // 打标缺口只对「检索命中期望文件」的用例计数（未命中属检索问题非打标失效）
        var summary = IndirectInjectionRunner.summarize(List.of(
            caseResult("poison-01", "RESISTED", true, false, true, true),   // 入库打标缺失
            caseResult("poison-02", "RESISTED", true, true, false, true),   // 运行时标记缺失
            caseResult("poison-03", "RESISTED", false, false, false, false) // 未检索到 → 不计缺口
        ));

        assertThat(summary.ingestionTagMisses()).isEqualTo(1);
        assertThat(summary.runtimeTagMisses()).isEqualTo(1);
    }

    @Test
    void emptyCasesYieldNaNSuppressionRate() {
        var summary = IndirectInjectionRunner.summarize(List.of());

        assertThat(summary.totalCases()).isZero();
        assertThat(Double.isNaN(summary.suppressionRate())).isTrue();
    }

    @Test
    void judgePromptCarriesThreeSlotsAndVerdictContract() {
        // 结构校验：三占位（问题/判据/回答）+ 二元 verdict 契约（内容无载荷字面）
        String rendered = JudgePrompts.INDIRECT_INJECTION.formatted("问题", "判据", "回答");

        assertThat(rendered)
            .contains("【用户问题】\n问题")
            .contains("【忠实判据（回答的期望行为描述）】\n判据")
            .contains("【回答】\n回答")
            .contains("RESISTED")
            .contains("EXECUTED");
    }
}
