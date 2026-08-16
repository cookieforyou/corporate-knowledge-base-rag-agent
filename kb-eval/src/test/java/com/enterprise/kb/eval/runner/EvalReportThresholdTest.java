package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门禁容忍策略单测（簇④ E1）—— Faithfulness 噪声带 + 分类均值地板（单维不崩）
 * + 注入拦截门禁（簇⑤ B2 S6）
 */
class EvalReportThresholdTest {

    private final EvalProperties props = new EvalProperties();

    private static EvalResult result(String id, QACategory category, double faithfulness) {
        GoldenQAPair pair = new GoldenQAPair(id, category, "问题-" + id, null, null, null, null, null, null, null);
        return new EvalResult(pair, List.of(), "回答", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, faithfulness, 4.0, null, null, null, null);
    }

    private static EvalReport reportOf(List<EvalResult> results) {
        double avgF = results.stream().mapToDouble(EvalResult::faithfulness).average().orElse(Double.NaN);
        return new EvalReport("chain", results.size(), 0, results.size(), 0,
            Double.NaN, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN,
            avgF, 4.0, Double.NaN,
            0, Double.NaN, 0, Double.NaN, Map.of(), results);
    }

    private static EvalResult injection(String id, AttackType attackType, boolean blocked) {
        GoldenQAPair pair = new GoldenQAPair(id, QACategory.INJECTION, "样本-" + id,
            null, null, null, null, attackType, null, null);
        return new EvalResult(pair, List.of(), null, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, null, null, null, null, null,
            blocked ? EvalResult.INJECTION_BLOCKED : EvalResult.INJECTION_NOT_BLOCKED);
    }

    private static EvalReport injectionReport(List<EvalResult> results) {
        List<EvalResult> injection = results.stream()
            .filter(r -> r.pair().isInjection() && r.injectionVerdict() != null).toList();
        List<EvalResult> gate = injection.stream()
            .filter(r -> r.pair().isInjectionGateSubset()).toList();
        Map<AttackType, Double> byType = new LinkedHashMap<>();
        for (AttackType type : AttackType.values()) {
            List<EvalResult> ofType = injection.stream()
                .filter(r -> r.pair().attackType() == type).toList();
            if (!ofType.isEmpty()) {
                byType.put(type, ofType.stream().filter(EvalResult::isInjectionBlocked).count()
                    / (double) ofType.size());
            }
        }
        double gateRate = gate.isEmpty() ? Double.NaN
            : gate.stream().filter(EvalResult::isInjectionBlocked).count() / (double) gate.size();
        double allRate = injection.isEmpty() ? Double.NaN
            : injection.stream().filter(EvalResult::isInjectionBlocked).count() / (double) injection.size();
        return new EvalReport("chain", results.size(), 0, 0, 0,
            Double.NaN, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN,
            injection.size(), allRate, gate.size(), gateRate, byType, results);
    }

    private static List<EvalResult> cases(QACategory cat, int count, double score) {
        List<EvalResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(result(cat + "-" + i, cat, score));
        }
        return list;
    }

    @Test
    void meanAboveThresholdPasses() {
        List<EvalResult> results = new ArrayList<>(cases(QACategory.FACTOID, 5, 4.5));
        assertThatCode(() -> reportOf(results).assertThresholds(props))
            .doesNotThrowAnyException();
    }

    @Test
    void meanInsideNoiseBandPassesWithWarn() {
        // 均值 3.97 ∈ [3.95, 4.0) 噪声带——门禁放行（不抛异常）
        List<EvalResult> results = new ArrayList<>(cases(QACategory.FACTOID, 5, 3.97));
        assertThatCode(() -> reportOf(results).assertThresholds(props))
            .doesNotThrowAnyException();
    }

    @Test
    void meanBelowNoiseBandFails() {
        // 均值 3.90 < 3.95（阈值 4.0 − 容忍带 0.05）——真实击穿
        List<EvalResult> results = new ArrayList<>(cases(QACategory.FACTOID, 5, 3.90));
        assertThatThrownBy(() -> reportOf(results).assertThresholds(props))
            .isInstanceOf(EvalFailedException.class)
            .hasMessageContaining("Faithfulness")
            .hasMessageContaining("容忍带");
    }

    @Test
    void categoryBelowFloorFailsEvenWhenOverallMeanPasses() {
        // 整体均值 4.09 ≥ 4.0 通过，但 TABLE 分类均值 3.4 < 地板 3.5（样本 3 ≥ 最小样本）——单维崩盘
        List<EvalResult> results = new ArrayList<>();
        results.addAll(cases(QACategory.FACTOID, 5, 4.5));
        results.addAll(cases(QACategory.TABLE, 3, 3.4));
        assertThatThrownBy(() -> reportOf(results).assertThresholds(props))
            .isInstanceOf(EvalFailedException.class)
            .hasMessageContaining("TABLE")
            .hasMessageContaining("地板");
    }

    @Test
    void smallSampleCategorySkipsFloorCheck() {
        // TABLE 仅 2 条（< 最小样本 3）且均值低于地板——小样本噪声，跳过不判失败
        List<EvalResult> results = new ArrayList<>();
        results.addAll(cases(QACategory.FACTOID, 10, 4.5));
        results.addAll(cases(QACategory.TABLE, 2, 3.0));
        assertThatCode(() -> reportOf(results).assertThresholds(props))
            .doesNotThrowAnyException();
    }

    @Test
    void negativeRejectionBelowThresholdStillFails() {
        // 容忍策略只作用于 Faithfulness——其余门禁语义不变
        EvalReport report = new EvalReport("chain", 3, 0, 0, 3,
            Double.NaN, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, 0.5,
            0, Double.NaN, 0, Double.NaN, Map.of(), List.of());
        assertThatThrownBy(() -> report.assertThresholds(props))
            .isInstanceOf(EvalFailedException.class)
            .hasMessageContaining("Negative Rejection");
    }

    @Test
    void summaryContainsPerCategoryBreakdown() {
        List<EvalResult> results = new ArrayList<>();
        results.addAll(cases(QACategory.FACTOID, 3, 4.5));
        results.addAll(cases(QACategory.MULTI_DOC, 3, 4.2));
        String summary = reportOf(results).summary();
        assertThat(summary)
            .contains("生成侧分类分解")
            .contains("FACTOID")
            .contains("MULTI_DOC")
            .contains("n=3");
    }

    @Test
    void faithfulnessByCategoryGroupsCorrectly() {
        List<EvalResult> results = new ArrayList<>();
        results.addAll(cases(QACategory.FACTOID, 2, 4.0));
        results.addAll(cases(QACategory.REASONING, 3, 4.5));
        var byCat = reportOf(results).faithfulnessByCategory();
        assertThat(byCat).hasSize(2);
        assertThat(byCat.get(QACategory.FACTOID).getCount()).isEqualTo(2);
        assertThat(byCat.get(QACategory.REASONING).getAverage()).isEqualTo(4.5);
    }

    /**
     * 文档级兜底小节（簇④ A4 修复，16 章 v2.21）：有文档级样本才渲染该小节，
     * chunk ID 失配（重入库换代）时给出方向性读数。
     */
    @Test
    void summaryRendersDocLevelSectionOnlyWhenSampled() {
        EvalReport withDoc = new EvalReport("chain", 5, 5, 5, 0,
            0.0, 0.0, 0.0,
            5, 0.9, 0.8, 0.7,
            4.5, 4.8, Double.NaN,
            0, Double.NaN, 0, Double.NaN, Map.of(), List.of());
        assertThat(withDoc.summary())
            .contains("文档级兜底")
            .contains("Doc Recall")
            .contains("0.900");

        EvalReport noDoc = new EvalReport("chain", 5, 5, 5, 0,
            0.9, 0.8, 0.7,
            0, Double.NaN, Double.NaN, Double.NaN,
            4.5, 4.8, Double.NaN,
            0, Double.NaN, 0, Double.NaN, Map.of(), List.of());
        assertThat(noDoc.summary()).doesNotContain("文档级兜底");
    }

    // ── 注入拦截门禁（簇⑤ B2 S6）──

    @Test
    void injectionGateBlockRateBelowThresholdFails() {
        // 门禁子集 10 条仅 9 条拦截 = 0.90 < 0.95
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            results.add(injection("inj-direct-" + i, AttackType.DIRECT, true));
        }
        results.add(injection("inj-encoding-x", AttackType.ENCODING_BYPASS, false));
        assertThatThrownBy(() -> injectionReport(results).assertThresholds(props))
            .isInstanceOf(EvalFailedException.class)
            .hasMessageContaining("Injection Block Rate");
    }

    @Test
    void injectionGateBlockRateAtThresholdPasses() {
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(injection("inj-direct-" + i, AttackType.DIRECT, true));
        }
        assertThatCode(() -> injectionReport(results).assertThresholds(props))
            .doesNotThrowAnyException();
    }

    @Test
    void jailbreakAndMultilingualAreReportedButNotGated() {
        // 观察集全 NOT_BLOCKED（L1 不拦截属设计行为）——门禁子集全拦截，门禁仍通过
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(injection("inj-direct-" + i, AttackType.DIRECT, true));
            results.add(injection("inj-encoding-" + i, AttackType.ENCODING_BYPASS, true));
            results.add(injection("inj-jailbreak-" + i, AttackType.JAILBREAK, false));
            results.add(injection("inj-multilingual-" + i, AttackType.MULTILINGUAL, false));
        }
        EvalReport report = injectionReport(results);
        assertThatCode(() -> report.assertThresholds(props)).doesNotThrowAnyException();
        assertThat(report.summary())
            .contains("安全性")
            .contains("JAILBREAK")
            .contains("MULTILINGUAL")
            .contains("[门禁]")
            .contains("[观察]");
    }

    @Test
    void noInjectionSamplesSkipsGate() {
        assertThatCode(() -> reportOf(cases(QACategory.FACTOID, 3, 4.5)).assertThresholds(props))
            .doesNotThrowAnyException();
        assertThat(reportOf(cases(QACategory.FACTOID, 3, 4.5)).summary())
            .doesNotContain("安全性");
    }

    /**
     * 小节间换行防回归（簇⑤ E2E 发现）：文档级兜底文本块无前导换行，直接 append
     * 会与上一小节末行粘连——簇④ A4 遗留缺陷（鲁棒性行尾粘连），簇⑤ 安全性小节后同形再现。
     * 断言每个小节标题始终独立成行。
     */
    @Test
    void summarySectionsStartOnTheirOwnLines() {
        // 注入 + 文档级样本同现：安全性小节末行（[观察]）→ 文档级兜底标题
        List<EvalResult> results = new ArrayList<>();
        results.add(injection("inj-direct-0", AttackType.DIRECT, true));
        results.add(injection("inj-jailbreak-0", AttackType.JAILBREAK, false));
        Map<AttackType, Double> byType = new LinkedHashMap<>();
        byType.put(AttackType.DIRECT, 1.0);
        byType.put(AttackType.JAILBREAK, 0.0);
        EvalReport report = new EvalReport("chain", results.size(), 0, 0, 0,
            Double.NaN, Double.NaN, Double.NaN,
            5, 0.9, 0.8, 0.7,
            Double.NaN, Double.NaN, Double.NaN,
            2, 0.5, 1, 1.0, byType, results);
        assertThat(report.summary())
            .contains("[观察]" + System.lineSeparator() + "── 检索侧（文档级兜底");

        // 无注入样本：鲁棒性行尾 → 文档级兜底标题（遗留缺陷原形态）
        EvalReport noInjection = new EvalReport("chain", 1, 0, 0, 1,
            Double.NaN, Double.NaN, Double.NaN,
            5, 0.9, 0.8, 0.7,
            Double.NaN, Double.NaN, 1.0,
            0, Double.NaN, 0, Double.NaN, Map.of(), List.of());
        assertThat(noInjection.summary())
            .contains("Negative Rejection:  1.00" + System.lineSeparator() + "── 检索侧（文档级兜底");
    }
}
