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
 * RRF (Reciprocal Rank Fusion) 融合器（设计文档 10.4；簇④ N 路泛化）
 *
 * <p>公式：RRF_score(d) = Σ 1 / (K + rank_i(d))，K 经 {@link RetrievalProperties#getRrfK()}
 * 注入（rag.retrieval.rrf-k，标准常数默认 60）。只消费排名不消费原始分数，
 * 天然免疫向量相似度 / BM25 分数 / 图谱贡献分的尺度差异。
 *
 * <p><b>N 路泛化（簇④ 5.2）</b>：主签名 {@link #fuse(Map, int)} 以「路标识 → 该路
 * 有序命中」为入参，路数与路名开放（vector / bm25 / graph / …），排名元数据
 * 按 {@code {路名}_rank} 键写出；旧双路签名 {@link #fuse(List, List, int)} 委派
 * 兼容（调用方零改动语义不变——双路序与键名逐位一致）。融合常数、降权语义、
 * metadata 禁 null 纪律全部不变。
 *
 * <p>输出 Document 携带完整溯源元数据（各路排名/融合分），
 * 供检索调试台（2.14）与 SSE TRACE（2.12）透传；metadata 遵守
 * Spring AI「禁止 null 值」约束，缺位路径不写对应键。
 *
 * <p><b>入库打标降权（安全簇④ D2，设计 §12.8）</b>：携带 {@code injection_hit=true}
 * 元数据的 chunk 在融合分计算后乘衰减系数（{@code rag.retrieval.injection-hit.demote}，
 * 默认关）。降权只改融合分不改排名元数据，rerank 降级路径消费同一衰减分。
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
     * N 路融合（簇④ 泛化主签名）。
     *
     * @param routeHits 路标识 → 该路命中列表（各路按自身分数降序，id = chunkId）；
     *                  建议 {@link LinkedHashMap} 保持路序稳定（仅影响元数据序，不影响融合分）
     * @param limit     融合结果上限（recallSize）
     */
    public List<Document> fuse(Map<String, List<Document>> routeHits, int limit) {
        int rrfK = properties.getRrfK();
        Map<String, FusedEntry> table = new LinkedHashMap<>();

        for (Map.Entry<String, List<Document>> route : routeHits.entrySet()) {
            String routeName = route.getKey();
            List<Document> hits = route.getValue();
            if (hits == null) {
                continue;
            }
            for (int i = 0; i < hits.size(); i++) {
                Document d = hits.get(i);
                table.computeIfAbsent(d.getId(), k -> new FusedEntry(d))
                    .mergeFrom(d)
                    .setRank(routeName, i + 1);
            }
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

    /**
     * 双路兼容签名（簇④ 前既有调用方形态）：委派 N 路，语义逐位一致。
     *
     * @param vectorHits 向量路命中（按相似度降序，id = chunkId）
     * @param bm25Hits   BM25 路命中（按 BM25 分降序，id = chunkId）
     * @param limit      融合结果上限（recallSize）
     */
    public List<Document> fuse(List<Document> vectorHits, List<Document> bm25Hits, int limit) {
        Map<String, List<Document>> routeHits = new LinkedHashMap<>();
        routeHits.put("vector", vectorHits);
        routeHits.put("bm25", bm25Hits);
        return fuse(routeHits, limit);
    }

    /** 融合中间态：不修改输入 Document，避免共享实例副作用 */
    private static class FusedEntry {
        final String chunkId;
        final String content;
        final Map<String, Object> metadata;
        /** 路标识 → 该路排名（1-based）；缺位路不载键（metadata 禁 null） */
        final Map<String, Integer> routeRanks = new LinkedHashMap<>();
        double fusionScore;

        FusedEntry(Document d) {
            this.chunkId = d.getId();
            this.content = d.getText();
            this.metadata = new HashMap<>(d.getMetadata());
        }

        /** 后续路带来的 file_name/page_num/实体命中等字段并入（先到优先，取并集） */
        FusedEntry mergeFrom(Document d) {
            d.getMetadata().forEach(this.metadata::putIfAbsent);
            return this;
        }

        FusedEntry setRank(String routeName, int rank) {
            routeRanks.putIfAbsent(routeName, rank);
            return this;
        }

        void computeFusionScore(int rrfK) {
            double score = 0;
            for (Integer rank : routeRanks.values()) {
                score += 1.0 / (rrfK + rank);
            }
            this.fusionScore = score;
        }

        /** 入库打标判定（安全簇④ D2）：各路元数据并集携带 injection_hit=true 即判中 */
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
            routeRanks.forEach((routeName, rank) -> meta.put(routeName + "_rank", rank));
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
