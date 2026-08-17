package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RRF 融合纯函数单测（指标经 SimpleMeterRegistry 承接，无外部依赖）
 */
class RrfFusionTest {

    private final RetrievalProperties properties = new RetrievalProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AiBusinessMetrics metrics = new AiBusinessMetrics(registry);
    private final RrfFusion fusion = new RrfFusion(properties, metrics);

    private Document doc(String id, Map<String, Object> meta) {
        return Document.builder().id(id).text("content-" + id).metadata(meta).build();
    }

    @Test
    void fuse_bothPathsHitSameDoc_scoresSummed() {
        // 同一 chunk 向量路第 1、BM25 路第 2：fusion = 1/(60+1) + 1/(60+2)
        List<Document> vector = List.of(doc("a", Map.of("vector_score", 0.91)));
        List<Document> bm25 = List.of(doc("x", Map.of("bm25_score", 12.5)), doc("a", Map.of("bm25_score", 9.0)));

        List<Document> fused = fusion.fuse(vector, bm25, 10);

        Document a = fused.stream().filter(d -> d.getId().equals("a")).findFirst().orElseThrow();
        int rrfK = properties.getRrfK();
        double expected = 1.0 / (rrfK + 1) + 1.0 / (rrfK + 2);
        assertEquals(expected, (Double) a.getMetadata().get("fusion_score"), 1e-12);
        assertEquals(1, a.getMetadata().get("vector_rank"));
        assertEquals(2, a.getMetadata().get("bm25_rank"));
        // 双路原始得分均保留
        assertEquals(0.91, a.getMetadata().get("vector_score"));
        assertEquals(9.0, a.getMetadata().get("bm25_score"));
    }

    @Test
    void fuse_orderByFusionScoreDesc_andDualHitRanksAboveSingleHit() {
        // a 双路命中（rank 2+2）；v1 仅向量第 1；b1 仅 BM25 第 1
        List<Document> vector = List.of(doc("v1", Map.of()), doc("a", Map.of()));
        List<Document> bm25 = List.of(doc("b1", Map.of()), doc("a", Map.of()));

        List<Document> fused = fusion.fuse(vector, bm25, 10);

        int rrfK = properties.getRrfK();
        double dualScore = 2.0 / (rrfK + 2);
        double singleScore = 1.0 / (rrfK + 1);
        assertTrue(dualScore > singleScore, "双路命中应排在单路之前");
        assertEquals("a", fused.get(0).getId());
        assertEquals(3, fused.size());
    }

    @Test
    void fuse_singlePathOnly_otherRankKeyAbsent() {
        List<Document> vector = List.of(doc("v1", Map.of("vector_score", 0.8)));

        List<Document> fused = fusion.fuse(vector, List.of(), 10);

        assertEquals(1, fused.size());
        Document d = fused.get(0);
        assertEquals(1, d.getMetadata().get("vector_rank"));
        // Spring AI metadata 禁 null：缺位路径的键不写入
        assertFalse(d.getMetadata().containsKey("bm25_rank"));
        assertEquals(1.0 / (properties.getRrfK() + 1), (Double) d.getMetadata().get("fusion_score"), 1e-12);
    }

    @Test
    void fuse_respectsLimit() {
        List<Document> vector = List.of(doc("a", Map.of()), doc("b", Map.of()), doc("c", Map.of()));

        assertEquals(2, fusion.fuse(vector, List.of(), 2).size());
    }

    @Test
    void fuse_emptyBothPaths() {
        assertTrue(fusion.fuse(List.of(), List.of(), 10).isEmpty());
    }

    @Test
    void fuse_metadataUnion_bm25OnlyFieldsMerged() {
        // file_name 仅 BM25 路携带（向量元数据无此键的场景）→ 融合后并入
        List<Document> vector = List.of(doc("a", Map.of("chunk_type", "TEXT")));
        List<Document> bm25 = List.of(doc("a", Map.of("file_name", "手册.pdf", "page_num", 7)));

        Document fused = fusion.fuse(vector, bm25, 10).get(0);

        assertEquals("TEXT", fused.getMetadata().get("chunk_type"));
        assertEquals("手册.pdf", fused.getMetadata().get("file_name"));
        assertEquals(7, fused.getMetadata().get("page_num"));
    }

    // ── 入库打标降权（安全簇④ D2，§9 定案④默认关）──

    @Test
    void demoteDisabledByDefault_hitChunkScoreZeroDrift() {
        // 缺省形态：injection_hit chunk 融合分与排序零变化，计数零
        List<Document> vector = List.of(doc("hit", Map.of("injection_hit", true)), doc("clean", Map.of()));

        List<Document> fused = fusion.fuse(vector, List.of(), 10);

        assertEquals(1.0 / (properties.getRrfK() + 1), (Double) fused.get(0).getMetadata().get("fusion_score"), 1e-12);
        assertEquals("hit", fused.get(0).getId());
        assertEquals(0.0, registry.counter("rag.retrieval.injection-hit.demoted").count());
    }

    @Test
    void demoteEnabled_hitChunkScoreDecayedAndReordered() {
        properties.getInjectionHit().getDemote().setEnabled(true);
        properties.getInjectionHit().getDemote().setFactor(0.1);
        // hit 双路命中原本排首；衰减 0.1 后应让位于单路命中的 clean
        List<Document> vector = List.of(doc("clean", Map.of()), doc("hit", Map.of("injection_hit", true)));
        List<Document> bm25 = List.of(doc("hit", Map.of("injection_hit", true)));

        List<Document> fused = fusion.fuse(vector, bm25, 10);

        assertEquals("clean", fused.get(0).getId());
        Document hit = fused.get(1);
        int rrfK = properties.getRrfK();
        double expected = (1.0 / (rrfK + 2) + 1.0 / (rrfK + 1)) * 0.1;
        assertEquals(expected, (Double) hit.getMetadata().get("fusion_score"), 1e-12);
        // 排名元数据保持原值可溯（降权只改融合分）
        assertEquals(2, hit.getMetadata().get("vector_rank"));
        assertEquals(1, hit.getMetadata().get("bm25_rank"));
        assertEquals(1.0, registry.counter("rag.retrieval.injection-hit.demoted").count());
    }

    @Test
    void demoteEnabled_cleanChunksUntouched() {
        properties.getInjectionHit().getDemote().setEnabled(true);
        List<Document> vector = List.of(doc("a", Map.of()), doc("b", Map.of()));

        List<Document> fused = fusion.fuse(vector, List.of(), 10);

        assertEquals(1.0 / (properties.getRrfK() + 1), (Double) fused.get(0).getMetadata().get("fusion_score"), 1e-12);
        assertEquals(0.0, registry.counter("rag.retrieval.injection-hit.demoted").count());
    }
}
