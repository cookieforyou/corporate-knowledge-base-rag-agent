package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.dataset.GoldenQAPair;

import java.util.List;

/**
 * 单条 Golden 用例的评估结果
 *
 * <p>检索侧指标（recall/mrr/contextPrecision）在 expectedChunkIds 为空时为 NaN，
 * 文档级兜底指标（docRecall/docMrr/docContextPrecision）在 expectedDocs 为空时
 * 为 NaN，聚合时均按「非 NaN 才计入」处理。
 */
public record EvalResult(
    GoldenQAPair pair,
    List<RetrievalProbe.ProbeHit> hits,
    String answer,
    double recall,
    double mrr,
    double contextPrecision,
    double docRecall,           // 文档级兜底（簇④ A4 修复，16 章 v2.21）
    double docMrr,
    double docContextPrecision,
    Double faithfulness,        // NEGATIVE / INJECTION 用例为 null
    Double responseRelevancy,   // NEGATIVE / INJECTION 用例为 null
    String rejectionVerdict,    // 仅 NEGATIVE 用例：REJECTED / PARTIAL / NOT_REJECTED
    Double rejectionScore,      // 仅 NEGATIVE 用例：5/3/1
    String judgeReason,
    String injectionVerdict,    // 仅 INJECTION 用例（簇⑤ B2 S6）：BLOCKED / NOT_BLOCKED（L1 链）
    String l2InjectionVerdict,  // 仅 INJECTION 用例且 eval.guardrail.l2-enabled（安全簇⑤ E2）：
                                // L1+L2 联合链判定 BLOCKED / NOT_BLOCKED；关闭时 null
    String l2RawVerdict,        // L2 二判原始裁决（簇② 批5 路径 a，度量盲区清偿）：
                                // PASS / SUSPECT / BLOCK（显式判定）/ FAIL_OPEN（二判故障回落）/
                                // NOT_JUDGED（判定器缺席）——值域源 SemanticInjectionAdvisor
                                // （RAW_FAIL_OPEN/RAW_NOT_JUDGED）与 L2Verdict（VERDICT_*）；
                                // null = 联合链未判定（L2 关闭 或 L1 直拦免二判）
    // ── Phase 5 扩展指标（簇② 5.8，16 章 §16.2；观察带——人类校准前不入门禁）──
    Double answerCorrectness,   // 1-5 Judge；expectedAnswer 为空 → null 跳过（当前语料零标注）
    String citationVerdict,     // Citation Attribution 三步判定：SUPPORTED / NOT_SUPPORTED / NO_CITATION
    Double citationResolvableRate, // 第二步可解析率（未发出引用 → null）
    Double hallucinationRate,   // 无依据声明占比 0-1（Judge 声明级核查）
    String noiseVerdict,        // Noise Robustness：CONSISTENT / DRIFTED；仅噪声抽样用例，未抽中 → null
    String noiseAnswer          // Noise Robustness 答案 B（混噪评估侧生成，簇② 批2）：
                                // 人审 NRob 需对照 A/B 两答案——校准表材料；未抽中 → null
) {
    /** INJECTION 判定：护栏抛 PROMPT_INJECTION 被捕获（簇⑤ B2 S6） */
    public static final String INJECTION_BLOCKED = "BLOCKED";
    /** INJECTION 判定：护栏未拦截，请求到达生成（攻击样本穿透 L1） */
    public static final String INJECTION_NOT_BLOCKED = "NOT_BLOCKED";

    public boolean isNegative() {
        return pair.isNegative();
    }

    public boolean isInjectionBlocked() {
        return INJECTION_BLOCKED.equals(injectionVerdict);
    }

    /** L1+L2 联合链判定（安全簇⑤ E2）：联合链抛 PROMPT_INJECTION 被捕获 */
    public boolean isL2Blocked() {
        return INJECTION_BLOCKED.equals(l2InjectionVerdict);
    }
}
