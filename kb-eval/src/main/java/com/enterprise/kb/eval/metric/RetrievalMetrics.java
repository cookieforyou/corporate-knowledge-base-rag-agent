package com.enterprise.kb.eval.metric;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索质量指标 —— 纯函数实现，无外部依赖，可单元测试（设计文档 16.2）
 *
 * <p>指标定义对齐 RAGAS 语义：
 * <ul>
 *   <li><b>Recall@K</b>：Top-K 命中期望 Chunk 的比例 = |retrieved ∩ expected| / |expected|</li>
 *   <li><b>MRR</b>：首个命中的倒数排名（Mean Reciprocal Rank 的单样本形态）</li>
 *   <li><b>Context Precision</b>：RAGAS 排名加权形态 = Σ(precision@k × rel(k)) / |expected|</li>
 * </ul>
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {}

    /** Recall@K：期望 Chunk 在 Top-K 中的命中比例；expected 为空返回 NaN（调用方跳过） */
    public static double recallAtK(List<String> retrievedIds, List<String> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) return Double.NaN;
        Set<String> retrieved = new HashSet<>(retrievedIds);
        long hits = expectedIds.stream().filter(retrieved::contains).count();
        return (double) hits / expectedIds.size();
    }

    /** 单样本 Reciprocal Rank：首个命中的 1/rank，无命中为 0 */
    public static double reciprocalRank(List<String> retrievedIds, List<String> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) return Double.NaN;
        Set<String> expected = new HashSet<>(expectedIds);
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expected.contains(retrievedIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * Context Precision（RAGAS 排名加权）：Σ(precision@k × rel(k)) / |expected|
     *
     * <p>rel(k)=1 表示第 k 个检索结果属于期望集合；precision@k 为前 k 个结果中相关比例。
     * 命中的排名越靠前，得分越高。
     */
    public static double contextPrecision(List<String> retrievedIds, List<String> expectedIds) {
        if (expectedIds == null || expectedIds.isEmpty()) return Double.NaN;
        Set<String> expected = new HashSet<>(expectedIds);
        double weighted = 0.0;
        int relevantSeen = 0;
        for (int k = 1; k <= retrievedIds.size(); k++) {
            if (expected.contains(retrievedIds.get(k - 1))) {
                relevantSeen++;
                weighted += (double) relevantSeen / k;
            }
        }
        return weighted / expectedIds.size();
    }
}
