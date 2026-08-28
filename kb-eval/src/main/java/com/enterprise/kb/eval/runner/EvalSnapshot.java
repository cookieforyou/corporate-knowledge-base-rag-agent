package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 评估机读快照（簇② 5.9 批3，16 章 §16.5）——A/B 双跑差异报表的数据面。
 *
 * <p>每次全量评估后随报告落盘 {@code target/eval-results{-label}.json}：
 * 运行锚点（git 形态）+ 运行配置（可比性核验面）+ 聚合指标 + 逐用例读数。
 * {@code --eval.diff=<labelA,labelB>} 消费两份快照产出差异报表。
 *
 * <p><b>内容盲纪律</b>（敏感词交付红线同 IndirectInjectionRunner）：快照只携带
 * 用例 ID、数值指标、verdict 枚举与答案 SHA-256 指纹——不携带问句与回答正文，
 * 注入攻击样本字面不外泄至报告产物；答案变化经指纹比对检出（「回答是否变了」
 * 可度量，「回答内容」不经机读面传播）。
 */
public record EvalSnapshot(
    GitAnchor anchor,
    RunConfig runConfig,
    Aggregates aggregates,
    List<CaseScores> cases) {

    /**
     * 运行配置（A/B 可比性核验面）：diff 报告逐字段对照，不一致项标 ⚠——
     * 单变量 A/B 假设下除被验证变量（如 prompt 版本）外其余配置应一致。
     */
    public record RunConfig(
        String probe,
        int sampleSize,
        int topK,
        boolean retrievalOnly,
        String judgeModel,
        Double judgeTemperature,
        boolean judgeEnableThinking,
        boolean phase5Enabled,
        int noiseSampleSize,
        boolean guardrailL2Enabled) {}

    /** 聚合指标（EvalReport 聚合面投影；无样本维度为 NaN，diff 按「样本缺失」跳过） */
    public record Aggregates(
        String probeName,
        int totalPairs,
        int retrievalEvaluated,
        int generationEvaluated,
        int negativeEvaluated,
        double avgRecall,
        double avgMrr,
        double avgContextPrecision,
        int docRetrievalEvaluated,
        double avgDocRecall,
        double avgDocMrr,
        double avgDocContextPrecision,
        double avgFaithfulness,
        double avgResponseRelevancy,
        double negativeRejectionRate,
        int injectionEvaluated,
        double injectionBlockRate,
        int injectionGateEvaluated,
        double injectionGateBlockRate,
        Map<AttackType, Double> injectionBlockRateByAttackType,
        EvalReport.Phase5Metrics phase5) {}

    /** 逐用例读数（内容盲：无问句/回答正文，答案仅存 SHA-256 指纹） */
    public record CaseScores(
        String id,
        String category,
        String answerSha256,
        double recall,
        double mrr,
        double contextPrecision,
        double docRecall,
        double docMrr,
        double docContextPrecision,
        Double faithfulness,
        Double responseRelevancy,
        String rejectionVerdict,
        String injectionVerdict,
        String l2InjectionVerdict,
        String l2RawVerdict,
        Double answerCorrectness,
        String citationVerdict,
        Double citationResolvableRate,
        Double hallucinationRate,
        String noiseVerdict) {}

    public static EvalSnapshot from(EvalReport report, EvalProperties props, GitAnchor anchor) {
        RunConfig runConfig = new RunConfig(
            report.probeName(),
            props.getSampleSize(),
            props.getTopK(),
            props.isRetrievalOnly(),
            props.getJudge().getModel(),
            props.getJudge().getTemperature(),
            props.getJudge().isEnableThinking(),
            props.getMetrics().isPhase5Enabled(),
            props.getMetrics().getNoiseSampleSize(),
            props.getGuardrail().isL2Enabled());
        Aggregates aggregates = new Aggregates(
            report.probeName(),
            report.totalPairs(),
            report.retrievalEvaluated(),
            report.generationEvaluated(),
            report.negativeEvaluated(),
            report.avgRecall(),
            report.avgMrr(),
            report.avgContextPrecision(),
            report.docRetrievalEvaluated(),
            report.avgDocRecall(),
            report.avgDocMrr(),
            report.avgDocContextPrecision(),
            report.avgFaithfulness(),
            report.avgResponseRelevancy(),
            report.negativeRejectionRate(),
            report.injectionEvaluated(),
            report.injectionBlockRate(),
            report.injectionGateEvaluated(),
            report.injectionGateBlockRate(),
            report.injectionBlockRateByAttackType(),
            report.phase5() == null ? EvalReport.Phase5Metrics.EMPTY : report.phase5());
        List<CaseScores> cases = report.results().stream()
            .map(EvalSnapshot::caseScores).toList();
        return new EvalSnapshot(anchor, runConfig, aggregates, cases);
    }

    private static CaseScores caseScores(EvalResult r) {
        return new CaseScores(
            r.pair().id(),
            r.pair().category().name(),
            sha256(r.answer()),
            r.recall(), r.mrr(), r.contextPrecision(),
            r.docRecall(), r.docMrr(), r.docContextPrecision(),
            r.faithfulness(), r.responseRelevancy(),
            r.rejectionVerdict(), r.injectionVerdict(), r.l2InjectionVerdict(), r.l2RawVerdict(),
            r.answerCorrectness(), r.citationVerdict(), r.citationResolvableRate(),
            r.hallucinationRate(), r.noiseVerdict());
    }

    /** 答案 SHA-256 指纹（内容盲变化检测）；null 答案 → null */
    static String sha256(String text) {
        if (text == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
