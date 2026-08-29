package com.enterprise.kb.eval.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cohen's κ 评分者间一致性（簇② 5.8 批2 人类校准通道，16 章 §16.2）
 *
 * <p>κ = (po − pe) / (1 − pe)：po 实际一致率，pe 随机期望一致率（边际分布乘积）。
 * κ ≥ 0.80 为原校准目标；κ 悖论治理裁决（16 章 v2.80）后降为观察报告不阻断，
 * 前置判据改由名义一致率主判承接（「连续 2 轮」达成，观察带已接线，16 章 v2.82）。
 *
 * <p>两种标度：
 * <ul>
 *   <li>{@link #nominal} ——名义标度（CA verdict / HR 二值化 / NRob verdict）；</li>
 *   <li>{@link #weightedQuadratic} ——二次加权 κ（F / AC 的 1-5 序数评分，
 *       相邻档分歧惩罚轻于跨档分歧，避免序数数据被名义 κ 低估）。</li>
 * </ul>
 *
 * <p>退化语义：空样本对返回 NaN；边际分布退化到单一类别（pe=1）时全一致约定
 * 返回 1.0（如全 CONSISTENT），其余 κ 无定义返回 NaN——由报告层标注「未定」
 * 而非当作通过。
 */
public final class CohensKappa {

    private CohensKappa() {}

    /**
     * 名义 κ：两列同长度标签序列的一致性。
     * 标签按 equals 比较（调用方负责归一化：大小写、verdict 别名等）。
     */
    public static double nominal(List<String> a, List<String> b) {
        requireSameSize(a.size(), b.size());
        int n = a.size();
        if (n == 0) {
            return Double.NaN;
        }
        long agree = 0;
        Map<String, Long> marginA = new LinkedHashMap<>();
        Map<String, Long> marginB = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (a.get(i).equals(b.get(i))) {
                agree++;
            }
            marginA.merge(a.get(i), 1L, Long::sum);
            marginB.merge(b.get(i), 1L, Long::sum);
        }
        double po = (double) agree / n;
        double pe = 0.0;
        for (Map.Entry<String, Long> e : marginA.entrySet()) {
            Long nb = marginB.get(e.getKey());
            if (nb != null) {
                pe += ((double) e.getValue() / n) * ((double) nb / n);
            }
        }
        if (Math.abs(1.0 - pe) < 1e-12) {
            // 边际分布退化到单一类别：全一致（如全 CONSISTENT）约定 1.0，
            // 否则 κ 无定义（NaN，报告层标「未定」而非通过）
            return Math.abs(1.0 - po) < 1e-12 ? 1.0 : Double.NaN;
        }
        return (po - pe) / (1.0 - pe);
    }

    /**
     * 二次加权 κ（序数 1..categories）：分歧权重 = (i−j)²/(categories−1)²，
     * κ_w = 1 − 观察加权分歧 / 期望加权分歧。categories=2 时退化为名义 κ。
     *
     * @throws IllegalArgumentException 评分越界（&lt;1 或 &gt;categories）或两列不等长
     */
    public static double weightedQuadratic(List<Integer> a, List<Integer> b, int categories) {
        requireSameSize(a.size(), b.size());
        int n = a.size();
        if (n == 0) {
            return Double.NaN;
        }
        double[][] o = new double[categories][categories];
        for (int i = 0; i < n; i++) {
            int ra = a.get(i);
            int rb = b.get(i);
            if (ra < 1 || ra > categories || rb < 1 || rb > categories) {
                throw new IllegalArgumentException("评分越界 1.." + categories + "：" + ra + " vs " + rb);
            }
            o[ra - 1][rb - 1]++;
        }
        double denom = (categories - 1.0) * (categories - 1.0);
        double[] rowMargin = new double[categories];
        double[] colMargin = new double[categories];
        for (int i = 0; i < categories; i++) {
            for (int j = 0; j < categories; j++) {
                rowMargin[i] += o[i][j] / n;
                colMargin[j] += o[i][j] / n;
            }
        }
        double observed = 0.0;
        double expected = 0.0;
        for (int i = 0; i < categories; i++) {
            for (int j = 0; j < categories; j++) {
                if (i == j) {
                    continue;
                }
                double weight = (i - j) * (i - j) / denom;
                observed += weight * o[i][j] / n;
                expected += weight * rowMargin[i] * colMargin[j];
            }
        }
        if (expected < 1e-12) {
            // 边际分布退化到单一类别：观察分歧必为 0 → 完美一致约定返回 1
            return observed < 1e-12 ? 1.0 : Double.NaN;
        }
        return 1.0 - observed / expected;
    }

    /**
     * E1 口径一致率（簇④ 延续）：|a−b| ≤ 1 记一致。与 κ 并行报告——
     * κ 治随机一致校正后的真实一致性，本口径保留历史对照（目标 ≥85%）。
     */
    public static double withinOneAgreement(List<Integer> a, List<Integer> b) {
        requireSameSize(a.size(), b.size());
        int n = a.size();
        if (n == 0) {
            return Double.NaN;
        }
        long within = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(a.get(i) - b.get(i)) <= 1) {
                within++;
            }
        }
        return (double) within / n;
    }

    private static void requireSameSize(int sizeA, int sizeB) {
        if (sizeA != sizeB) {
            throw new IllegalArgumentException("两列评分对数量不一致：" + sizeA + " vs " + sizeB);
        }
    }
}
