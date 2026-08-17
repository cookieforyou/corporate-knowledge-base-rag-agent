package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.QACategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
    int docRetrievalEvaluated,       // 文档级兜底（簇④ A4 修复，16 章 v2.21）
    double avgDocRecall,
    double avgDocMrr,
    double avgDocContextPrecision,
    double avgFaithfulness,
    double avgResponseRelevancy,
    double negativeRejectionRate,
    int injectionEvaluated,            // 注入拦截（簇⑤ B2 S6，12.4.3 S6）
    double injectionBlockRate,         // 总体拦截率
    int injectionGateEvaluated,        // 门禁子集（DIRECT + ENCODING_BYPASS）样本数
    double injectionGateBlockRate,     // 门禁子集拦截率（≥ injectionBlockRate 阈值）
    Map<AttackType, Double> injectionBlockRateByAttackType,
    List<EvalResult> results
) {

    private static final Logger log = LoggerFactory.getLogger(EvalReport.class);

    /**
     * 门禁判定：仅对「有样本且低于阈值」的指标报错；无样本指标跳过（建基线期策略）。
     *
     * <p>Faithfulness 容忍策略（簇④ E1，16.4 v2.20）：
     * <ol>
     *   <li>**噪声带**：均值 ∈ [阈值−tolerance, 阈值) → WARN 不 FAIL（Judge 分数噪声，
     *       单次抖动不杀门禁）；低于 阈值−tolerance 才判真实击穿；</li>
     *   <li>**单维不崩**：整体均值可能被大类拉高掩盖分类崩盘——任一正向分类均值低于
     *       categoryFloor（样本数 ≥ categoryMinSamples 才检查）即 FAIL。</li>
     * </ol>
     */
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
        if (generationEvaluated > 0) {
            double hardFloor = t.getFaithfulness() - t.getFaithfulnessTolerance();
            if (avgFaithfulness < hardFloor) {
                failures.append(String.format(
                    "Faithfulness %.3f < 阈值 %.1f − 容忍带 %.2f = %.3f（样本 %d）%n",
                    avgFaithfulness, t.getFaithfulness(), t.getFaithfulnessTolerance(),
                    hardFloor, generationEvaluated));
            } else if (avgFaithfulness < t.getFaithfulness()) {
                log.warn(String.format(
                    "⚠️ Faithfulness %.3f 落入噪声带 [%.3f, %.1f)——门禁放行但需关注趋势",
                    avgFaithfulness, hardFloor, t.getFaithfulness()));
            }
            // 单维不崩：分类均值地板
            for (Map.Entry<QACategory, DoubleSummaryStatistics> e : faithfulnessByCategory().entrySet()) {
                DoubleSummaryStatistics stat = e.getValue();
                if (stat.getCount() >= t.getFaithfulnessCategoryMinSamples()
                        && stat.getAverage() < t.getFaithfulnessCategoryFloor()) {
                    failures.append(String.format(
                        "分类 %s Faithfulness 均值 %.3f < 地板 %.1f（样本 %d，单维崩盘）%n",
                        e.getKey(), stat.getAverage(), t.getFaithfulnessCategoryFloor(), stat.getCount()));
                }
            }
        }
        if (negativeEvaluated > 0 && negativeRejectionRate < t.getNegativeRejection()) {
            failures.append(String.format(
                "Negative Rejection %.2f < 阈值 %.2f（样本 %d）%n",
                negativeRejectionRate, t.getNegativeRejection(), negativeEvaluated));
        }
        // 注入拦截门禁（簇⑤ B2 S6）：仅对 L1 机制防域子集（DIRECT + ENCODING_BYPASS）门禁；
        // JAILBREAK / MULTILINGUAL / ENCODING_OPAQUE 为观察集——L1 不拦截属设计行为，只报告不门禁
        if (injectionGateEvaluated > 0 && injectionGateBlockRate < t.getInjectionBlockRate()) {
            failures.append(String.format(
                "Injection Block Rate（门禁子集 DIRECT+ENCODING_BYPASS）%.2f < 阈值 %.2f（样本 %d）%n",
                injectionGateBlockRate, t.getInjectionBlockRate(), injectionGateEvaluated));
        }

        if (!failures.isEmpty()) {
            throw new EvalFailedException("评估门禁未通过：\n" + failures);
        }
    }

    /** 正向用例按分类聚合 Faithfulness（NEGATIVE 与无评分用例剔除），分类名升序稳定输出 */
    public Map<QACategory, DoubleSummaryStatistics> faithfulnessByCategory() {
        return results.stream()
            .filter(r -> !r.isNegative() && r.faithfulness() != null)
            .collect(Collectors.groupingBy(r -> r.pair().category(),
                TreeMap::new, Collectors.summarizingDouble(EvalResult::faithfulness)));
    }

    public String summary() {
        StringBuilder sb = new StringBuilder(String.format("""
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
                Negative Rejection:  %s""",
            probeName, totalPairs, retrievalEvaluated, generationEvaluated, negativeEvaluated,
            fmt(avgRecall), fmt(avgMrr), fmt(avgContextPrecision),
            fmt(avgFaithfulness), fmt(avgResponseRelevancy),
            negativeEvaluated > 0 ? String.format("%.2f", negativeRejectionRate) : "无样本，跳过"));

        // 负向用例判定分解（v2.43 四批）：Negative Rejection 是门禁指标却原来零逐例
        // 可观测——逐条列出未规范拒答（PARTIAL/NOT_REJECTED）用例的 ID + 判定，
        // 支撑边界集/负向集的定位维护（内容盲形态：仅 ID + 判定，不回显问题内容）
        if (negativeEvaluated > 0) {
            List<EvalResult> notFullyRejected = results.stream()
                .filter(r -> r.pair().isNegative() && r.rejectionVerdict() != null
                    && !"REJECTED".equalsIgnoreCase(r.rejectionVerdict()))
                .sorted(Comparator.comparing(r -> r.pair().id()))
                .toList();
            if (!notFullyRejected.isEmpty()) {
                sb.append(System.lineSeparator())
                    .append(String.format("── 负向未规范拒答（%d 条，PARTIAL/NOT_REJECTED）──",
                        notFullyRejected.size()));
                for (EvalResult r : notFullyRejected) {
                    sb.append(String.format("%n  %-16s %s", r.pair().id(), r.rejectionVerdict()));
                }
            }
        }

        // 安全性（簇⑤ B2 S6）：注入拦截率——总体 + 门禁子集（DIRECT+ENCODING_BYPASS）
        // + 按攻击类型分解；JAILBREAK / MULTILINGUAL / ENCODING_OPAQUE 为观察集（L1 不拦截属设计行为）
        if (injectionEvaluated > 0) {
            sb.append(System.lineSeparator()).append("── 安全性（注入拦截）──");
            sb.append(String.format("%n拦截率（总体）:      %s（n=%d）", fmt(injectionBlockRate), injectionEvaluated));
            sb.append(String.format("%n拦截率（门禁子集）:  %s（n=%d，DIRECT+ENCODING_BYPASS）",
                fmt(injectionGateBlockRate), injectionGateEvaluated));
            for (Map.Entry<AttackType, Double> e : injectionBlockRateByAttackType.entrySet()) {
                long n = results.stream()
                    .filter(r -> r.pair().isInjection() && r.pair().attackType() == e.getKey())
                    .count();
                boolean gate = e.getKey() == AttackType.DIRECT || e.getKey() == AttackType.ENCODING_BYPASS;
                sb.append(String.format("%n  %-16s n=%-3d 拦截率=%s  %s",
                    e.getKey(), n, fmt(e.getValue()), gate ? "[门禁]" : "[观察]"));
            }
        }

        // 文档级兜底（簇④ A4 修复）：chunk ID 失配（重入库换代/解析漂移）时
        // chunk 级归零，此层以 file_name 匹配给出方向性读数；无门禁仅观测。
        // 前置换行显式补：文本块无前导换行，直接 append 会与上一小节末行粘连
        // （簇④ A4 遗留缺陷，簇⑤ E2E 发现并修复，防回归见 EvalReportThresholdTest）
        if (docRetrievalEvaluated > 0) {
            sb.append(System.lineSeparator()).append(String.format("""
                ── 检索侧（文档级兜底，n=%d）──
                Doc Recall:          %s
                Doc MRR:             %s
                Doc Context Prec.:   %s""",
                docRetrievalEvaluated,
                fmt(avgDocRecall), fmt(avgDocMrr), fmt(avgDocContextPrecision)));
        }

        // 生成侧分类分解（簇④ E1）：Judge 校准漂移与 A/B 对比的维度定位依据——
        // 整体均值可能掩盖单一分类的涨跌，逐分类列出样本数与 Faithfulness/Relevancy 均值
        Map<QACategory, DoubleSummaryStatistics> byCat = faithfulnessByCategory();
        if (!byCat.isEmpty()) {
            sb.append(System.lineSeparator()).append("── 生成侧分类分解 ──");
            for (Map.Entry<QACategory, DoubleSummaryStatistics> e : byCat.entrySet()) {
                double rrAvg = results.stream()
                    .filter(r -> r.pair().category() == e.getKey() && r.responseRelevancy() != null)
                    .mapToDouble(EvalResult::responseRelevancy).average().orElse(Double.NaN);
                sb.append(String.format("%n%-12s n=%-3d F=%s  RR=%s",
                    e.getKey(), e.getValue().getCount(),
                    String.format("%.3f", e.getValue().getAverage()), fmt(rrAvg)));
            }
        }

        // 逐用例检索明细：A/B 基线对比的 diff 分析依据（哪些用例收益、哪些持平）
        List<EvalResult> retrievalCases = results.stream()
            .filter(r -> !Double.isNaN(r.recall())).toList();
        if (!retrievalCases.isEmpty()) {
            sb.append(System.lineSeparator()).append("── 逐用例检索明细 ──");
            for (EvalResult r : retrievalCases) {
                sb.append(String.format("%n%-15s R=%.2f  MRR=%.2f  CP=%.2f",
                    r.pair().id(), r.recall(), r.mrr(), r.contextPrecision()));
            }
        }
        return sb.append(System.lineSeparator())
            .append("══════════════════════════════").toString();
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "无样本，跳过" : String.format("%.3f", v);
    }
}
