package com.enterprise.kb.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 检索调试结果（设计文档 10.7）：直调检索链路（不经 LLM）的全维度得分快照
 *
 * <p>面向检索调试台（2.14）与 Bad Case 排查：candidates 为双路原始命中的并集
 * （含未进最终 Top-N 的候选），得分全部来自 10.1 元数据约定，零额外埋点。
 */
public record RetrievalDebugResult(
    String query,
    String rewrittenQuery,
    Latency latencyMs,
    List<Candidate> candidates,
    Map<String, String> degradation
) {

    /** 各阶段耗时（ms）：rewrite 查询改写 / retrieval 多路并行召回（含图路，簇④）/ rerank 精排 / total 全链路 */
    public record Latency(long rewrite, long retrieval, long rerank, long total) {}

    /**
     * 单候选 Chunk 全维度得分：向量相似度/排名、BM25 分/排名、
     * Graph 贡献分/排名 + 命中实体（簇④，图路缺位时为 null）、RRF 融合分、
     * 重排分/排名、最终排名。未出现在某路的维度为 null。
     */
    public record Candidate(
        String chunkId,
        String fileName,
        Integer pageNum,
        String chunkType,
        String content,
        Double vectorScore,
        Integer vectorRank,
        Double bm25Score,
        Integer bm25Rank,
        Double graphScore,
        Integer graphRank,
        String graphEntityHits,
        Double fusionScore,
        Double rerankScore,
        Integer rerankRank,
        Integer finalRank
    ) {}
}
