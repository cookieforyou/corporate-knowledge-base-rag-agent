package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门禁容忍策略单测（簇④ E1）—— Faithfulness 噪声带 + 分类均值地板（单维不崩）
 */
class EvalReportThresholdTest {

    private final EvalProperties props = new EvalProperties();

    private static EvalResult result(String id, QACategory category, double faithfulness) {
        GoldenQAPair pair = new GoldenQAPair(id, category, "问题-" + id, null, null, null, null);
        return new EvalResult(pair, List.of(), "回答", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, faithfulness, 4.0, null, null, null);
    }

    private static EvalReport reportOf(List<EvalResult> results) {
        double avgF = results.stream().mapToDouble(EvalResult::faithfulness).average().orElse(Double.NaN);
        return new EvalReport("chain", results.size(), 0, results.size(), 0,
            Double.NaN, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN,
            avgF, 4.0, Double.NaN, results);
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
            Double.NaN, Double.NaN, 0.5, List.of());
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
            4.5, 4.8, Double.NaN, List.of());
        assertThat(withDoc.summary())
            .contains("文档级兜底")
            .contains("Doc Recall")
            .contains("0.900");

        EvalReport noDoc = new EvalReport("chain", 5, 5, 5, 0,
            0.9, 0.8, 0.7,
            0, Double.NaN, Double.NaN, Double.NaN,
            4.5, 4.8, Double.NaN, List.of());
        assertThat(noDoc.summary()).doesNotContain("文档级兜底");
    }
}
