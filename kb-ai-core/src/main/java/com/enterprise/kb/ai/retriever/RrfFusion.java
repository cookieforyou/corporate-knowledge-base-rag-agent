package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.commons.constant.Constants;
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
 * <p>公式：RRF_score(d) = Σ 1 / (K + rank_i(d))，K = {@link Constants#RRF_K}（标准常数 60）。
 * 只消费排名不消费原始分数，天然免疫向量相似度与 BM25 分数的尺度差异。
 *
 * <p>输出 Document 携带完整溯源元数据（双路得分/排名/融合分），
 * 供检索调试台（2.14）与 SSE TRACE（2.12）透传；metadata 遵守
 * Spring AI「禁止 null 值」约束，缺位路径不写对应键。
 */
@Component
public class RrfFusion {

    /**
     * 融合双路召回结果
     *
     * @param vectorHits 向量路命中（按相似度降序，id = chunkId）
     * @param bm25Hits   BM25 路命中（按 BM25 分降序，id = chunkId）
     * @param limit      融合结果上限（recallSize）
     */
    public List<Document> fuse(List<Document> vectorHits, List<Document> bm25Hits, int limit) {
        Map<String, FusedEntry> table = new LinkedHashMap<>();

        for (int i = 0; i < vectorHits.size(); i++) {
            Document d = vectorHits.get(i);
            table.computeIfAbsent(d.getId(), k -> new FusedEntry(d)).vectorRank = i + 1;
        }
        for (int i = 0; i < bm25Hits.size(); i++) {
            Document d = bm25Hits.get(i);
            table.computeIfAbsent(d.getId(), k -> new FusedEntry(d)).mergeFrom(d).bm25Rank = i + 1;
        }

        return table.values().stream()
            .peek(FusedEntry::computeFusionScore)
            .sorted(Comparator.comparingDouble(FusedEntry::fusionScore).reversed())
            .limit(limit)
            .map(FusedEntry::toDocument)
            .toList();
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

        void computeFusionScore() {
            double score = 0;
            if (vectorRank != null) score += 1.0 / (Constants.RRF_K + vectorRank);
            if (bm25Rank != null) score += 1.0 / (Constants.RRF_K + bm25Rank);
            this.fusionScore = score;
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
