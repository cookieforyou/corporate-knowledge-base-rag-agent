package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF (Reciprocal Rank Fusion) 融合器（设计文档 10.4）
 *
 * <p>公式：RRF_score(d) = Σ 1 / (K + rank_i(d))，K 经 {@link RetrievalProperties#getRrfK()}
 * 注入（rag.retrieval.rrf-k，标准常数默认 60）。只消费排名不消费原始分数，
 * 天然免疫向量相似度与 BM25 分数的尺度差异。
 *
 * <p>输出 Document 携带完整溯源元数据（双路得分/排名/融合分），
 * 供检索调试台（2.14）与 SSE TRACE（2.12）透传；metadata 遵守
 * Spring AI「禁止 null 值」约束，缺位路径不写对应键。
 *
 * <p><b>入库打标降权（安全簇④ D2，设计 §12.8）</b>：携带 {@code injection_hit=true}
 * 元数据的 chunk（S4 ETL 入库扫描打标，经 vectorMetadata / EsChunkDoc 契约携带至
 * 检索侧）在融合分计算后乘衰减系数（{@code rag.retrieval.injection-hit.demote}，
 * <b>默认关</b>——§9 定案④：先经 kb-eval 检索门禁 Recall/MRR 开关双跑度量影响
 * 再定开关口径）。降权只改融合分不改排名元数据，rerank 降级路径
 * （fusion_score 截断）消费同一衰减分，语义一致。
 */
@Component
public class RrfFusion {

    /**
     * 注入命中标记键（与 kb-etl SanitizingTransformer.INJECTION_HIT_KEY 同值——
     * kb-ai-core 不依赖 kb-etl，字面须同步；契约源在 kb-etl 侧）。
     */
    static final String INJECTION_HIT_KEY = "injection_hit";

    private final RetrievalProperties properties;
    private final AiBusinessMetrics metrics;

    public RrfFusion(RetrievalProperties properties, AiBusinessMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 融合双路召回结果
     *
     * @param vectorHits 向量路命中（按相似度降序，id = chunkId）
     * @param bm25Hits   BM25 路命中（按 BM25 分降序，id = chunkId）
     * @param limit      融合结果上限（recallSize）
     */
    public List<Document> fuse(List<Document> vectorHits, List<Document> bm25Hits, int limit) {
        int rrfK = properties.getRrfK();
        Map<String, FusedEntry> table = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            Document d = vectorHits.get(i);
            table.computeIfAbsent(d.getId(), k -> new FusedEntry(d)).vectorRank = i + 1;
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            Document d = bm25Hits.get(i);
            table.computeIfAbsent(d.getId(), k -> new FusedEntry(d)).mergeFrom(d).bm25Rank = i + 1;
        }

        RetrievalProperties.InjectionHit.Demote demote = properties.getInjectionHit().getDemote();
        int[] demotedCount = {0};
        List<Document> fused = table.values().stream()
            .peek(entry -> entry.computeFusionScore(rrfK))
            .peek(entry -> {
                if (demote.isEnabled() && entry.isInjectionHit()) {
                    entry.demoteBy(demote.getFactor());
                    demotedCount[0]++;
                }
            })
            .sorted(Comparator.comparingDouble(FusedEntry::fusionScore).reversed())
            .limit(limit)
            .map(FusedEntry::toDocument)
            .toList();
        if (demotedCount[0] > 0) {
            metrics.recordInjectionHitDemoted(demotedCount[0]);
        }
        return fused;
    }

    /** 融合中间态：不修改输入 Document，避免共享实例副作用 */
    private static class FusedEntry {
        final String chunkId;
        final String content;
        final Map<String, Object> metadata;
        Integer vectorRank;
        Integer bm25Rank;
        double fusionScore;

        FusedEntry(Document d) {
            this.chunkId = d.getId();
            this.content = d.getText();
            this.metadata = new HashMap<>(d.getMetadata());
        }

        /** BM25 路带来的 file_name/page_num 等字段并入（向量元数据优先，取并集） */
        FusedEntry mergeFrom(Document d) {
            d.getMetadata().forEach(this.metadata::putIfAbsent);
            return this;
        }

        void computeFusionScore(int rrfK) {
            double score = 0;
            if (vectorRank != null) score += 1.0 / (rrfK + vectorRank);
            if (bm25Rank != null) score += 1.0 / (rrfK + bm25Rank);
            this.fusionScore = score;
        }

        /** 入库打标判定（安全簇④ D2）：双路元数据并集携带 injection_hit=true 即判中 */
        boolean isInjectionHit() {
            return Boolean.TRUE.equals(metadata.get(INJECTION_HIT_KEY));
        }

        /** 融合分衰减（安全簇④ D2）：降权只改融合分，排名元数据保持原值可溯 */
        void demoteBy(double factor) {
            this.fusionScore *= factor;
        }

        double fusionScore() {
            return fusionScore;
        }

        Document toDocument() {
            // Spring AI metadata 禁止 null：缺位路径的排名键不写入
            Map<String, Object> meta = new HashMap<>(this.metadata);
            if (vectorRank != null) meta.put("vector_rank", vectorRank);
            if (bm25Rank != null) meta.put("bm25_rank", bm25Rank);
            meta.put("fusion_score", fusionScore);
            return Document.builder()
                .id(chunkId)
                .text(content)
                .metadata(meta)
                .score(fusionScore)
                .build();
        }
    }
}
