package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.dataset.GoldenQAPair;

import java.util.List;

/**
 * 单条 Golden 用例的评估结果
 *
 * <p>检索侧指标（recall/mrr/contextPrecision）在 expectedChunkIds 为空时为 NaN，
 * 聚合时按「非 NaN 才计入」处理。
 */
public record EvalResult(
    GoldenQAPair pair,
    List<RetrievalProbe.ProbeHit> hits,
    String answer,
    double recall,
    double mrr,
    double contextPrecision,
    Double faithfulness,        // NEGATIVE 用例为 null
    Double responseRelevancy,   // NEGATIVE 用例为 null
    String rejectionVerdict,    // 仅 NEGATIVE 用例：REJECTED / PARTIAL / NOT_REJECTED
    Double rejectionScore,      // 仅 NEGATIVE 用例：5/3/1
    String judgeReason
) {
    public boolean isNegative() {
        return pair.isNegative();
    }
}
