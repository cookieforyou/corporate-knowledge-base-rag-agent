package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;

import java.util.List;

/**
 * 评估报告 —— 聚合指标 + 门禁判定（设计文档 16.4 阈值表）
 *
 * <p>NaN 语义：检索侧指标对无期望标注的用例返回 NaN，聚合时仅统计非 NaN 样本，
 * 样本数为 0 的指标不参与门禁（如 Phase 2.16 初期 corpus 尚未标注时 Recall/MRR 不门禁）。
 */
public record EvalReport(
    String probeName,
    int totalPairs,
    int retrievalEvaluated,
    int generationEvaluated,
    int negativeEvaluated,
    double avgRecall,
    double avgMrr,
    double avgContextPrecision,
    double avgFaithfulness,
    double avgResponseRelevancy,
    double negativeRejectionRate,
    List<EvalResult> results
) {

    /** 门禁判定：仅对「有样本且低于阈值」的指标报错；无样本指标跳过（建基线期策略） */
    public void assertThresholds(EvalProperties props) {
        EvalProperties.Thresholds t = props.getThresholds();
        StringBuilder failures = new StringBuilder();

        if (retrievalEvaluated > 0) {
            if (avgRecall < t.getTopKRecall()) {
                failures.append(String.format(
                    "Top-K Recall %.3f < 阈值 %.2f（样本 %d）%n", avgRecall, t.getTopKRecall(), retrievalEvaluated));
            }
            if (avgMrr < t.getMrr()) {
                failures.append(String.format(
                    "MRR %.3f < 阈值 %.2f（样本 %d）%n", avgMrr, t.getMrr(), retrievalEvaluated));
            }
        }
        if (generationEvaluated > 0 && avgFaithfulness < t.getFaithfulness()) {
            failures.append(String.format(
                "Faithfulness %.2f < 阈值 %.1f（样本 %d）%n", avgFaithfulness, t.getFaithfulness(), generationEvaluated));
        }
        if (negativeEvaluated > 0 && negativeRejectionRate < t.getNegativeRejection()) {
            failures.append(String.format(
                "Negative Rejection %.2f < 阈值 %.2f（样本 %d）%n",
                negativeRejectionRate, t.getNegativeRejection(), negativeEvaluated));
        }

        if (!failures.isEmpty()) {
            throw new EvalFailedException("评估门禁未通过：\n" + failures);
        }
    }

    public String summary() {
        return String.format("""
                ══════════ 评估报告 ══════════
                检索探针:        %s
                用例总数:        %d（检索可评 %d / 生成可评 %d / 负向 %d）
                ── 检索侧 ──
                Top-K Recall:        %s
                MRR:                 %s
                Context Precision:   %s
                ── 生成侧 ──
                Faithfulness:        %s
                Response Relevancy:  %s
                ── 鲁棒性 ──
                Negative Rejection:  %s
                ══════════════════════════════""",
            probeName, totalPairs, retrievalEvaluated, generationEvaluated, negativeEvaluated,
            fmt(avgRecall), fmt(avgMrr), fmt(avgContextPrecision),
            fmt(avgFaithfulness), fmt(avgResponseRelevancy),
            negativeEvaluated > 0 ? String.format("%.2f", negativeRejectionRate) : "无样本，跳过");
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "无样本，跳过" : String.format("%.3f", v);
    }
}
