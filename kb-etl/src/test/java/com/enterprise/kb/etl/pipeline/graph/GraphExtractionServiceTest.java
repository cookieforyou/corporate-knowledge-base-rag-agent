package com.enterprise.kb.etl.pipeline.graph;

import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.GraphStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GraphExtractionService 单测（簇④）：编排语义——合并收敛 / 租户守卫 /
 * 维度快失败 / 越集关系丢弃 / 状态机流转。
 */
class GraphExtractionServiceTest {

    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private EntityExtractor entityExtractor;
    private EmbeddingModel embeddingModel;
    private GraphGateway graphGateway;
    private RedissonClient redissonClient;
    private RecordingListener listener;
    private GraphExtractionService service;

    /** 观测回调收集器（R1 SPI 的实现侧验证） */
    static class RecordingListener implements GraphExtractionListener {
        int started, succeeded, failed;

        @Override
        public void extractionStarted(String tenantId, String docId, int chunkCount) {
            started++;
        }

        @Override
        public void extractionSucceeded(String tenantId, String docId, int entityCount, int relationCount) {
            succeeded++;
        }

        @Override
        public void extractionFailed(String tenantId, String docId, String reason) {
            failed++;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        entityExtractor = mock(EntityExtractor.class);
        embeddingModel = mock(EmbeddingModel.class);
        graphGateway = mock(GraphGateway.class);
        redissonClient = mock(RedissonClient.class);
        listener = new RecordingListener();

        RRateLimiter limiter = mock(RRateLimiter.class);
        // 签名实证（redisson 4.6.1 javap）：tryAcquire(long permits, long timeout, TimeUnit)
        when(limiter.tryAcquire(anyLong(), any(Duration.class))).thenReturn(true);
        lenient().when(redissonClient.getRateLimiter(anyString())).thenReturn(limiter);
        // 批量化缺省桩（v2.78）：批量请求按输入序回 1024 维向量（带 index 归位材料）
        lenient().when(embeddingModel.embedForResponse(any())).thenAnswer(inv ->
            batchResponse(inv.getArgument(0), 1024));

        ObjectProvider<GraphExtractionListener> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(inv -> Stream.of(listener));

        service = new GraphExtractionService(documentRepository, chunkRepository, entityExtractor,
            embeddingModel, graphGateway, redissonClient, new GraphExtractionProperties(), provider);
    }

    private KbDocument doc(String tenantId) {
        KbDocument doc = new KbDocument();
        doc.setId("d1");
        doc.setTenantId(tenantId);
        doc.setStatus(com.enterprise.kb.domain.enums.DocumentStatus.SUCCESS);
        return doc;
    }

    private KbChunk chunk(String id, int index, String content) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setDocId("d1");
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setIsDeleted(false);
        return chunk;
    }

    private static ExtractionResult result(List<ExtractionResult.EntityExtraction> entities,
                                           List<ExtractionResult.RelationExtraction> relations) {
        return new ExtractionResult(entities, relations);
    }

    /** 批量嵌入响应构造（按输入序 + 显式 index，与供应商契约同形；
     *  null 容错——Mockito 桩注册期以 null 实参探测既有桩） */
    private static EmbeddingResponse batchResponse(List<String> texts, int dimensions) {
        List<Embedding> embeddings = new ArrayList<>();
        if (texts != null) {
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(new float[dimensions], i));
            }
        }
        return new EmbeddingResponse(embeddings);
    }

    @Test
    void successfulExtractionMergesEntitiesAndWritesGraph() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 在年度发布会上正式推出了旗舰产品。"),
            chunk("c2", 1, "旗舰产品 X1 采用模块化设计，面向企业客户。"),
            chunk("c3", 2, "过短")));
        when(entityExtractor.extract(any(), anyString(), any())).thenAnswer(inv -> {
            String text = inv.getArgument(1);
            if (text.contains("Alpha Corp")) {
                return result(
                    List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "发布 X1 的制造企业")),
                    List.of(new ExtractionResult.RelationExtraction("Alpha Corp", "X1",
                        "PRODUCED_BY", "X1 由 Alpha Corp 发布")));
            }
            if (text.contains("X1")) {
                return result(List.of(new ExtractionResult.EntityExtraction("X1", "PRODUCT", "模块化企业产品")),
                    List.of());
            }
            return result(List.of(), List.of());
        });

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isTrue();
        assertThat(doc.getGraphStatus()).isEqualTo(GraphStatus.COMPLETED);
        assertThat(doc.getGraphUpdatedAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRecords.EntityWrite>> entities = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRecords.RelationWrite>> relations = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRecords.ChunkAnchor>> anchors = ArgumentCaptor.forClass(List.class);
        verify(graphGateway).replaceDocumentGraph(eq("t1"), eq("d1"),
            anchors.capture(), entities.capture(), relations.capture());

        assertThat(entities.getValue()).hasSize(2);
        assertThat(relations.getValue()).hasSize(1);
        assertThat(relations.getValue().get(0).relationType()).isEqualTo("PRODUCED_BY");
        assertThat(anchors.getValue()).hasSize(2);    // 两个含实体 chunk 落锚
        assertThat(listener.started).isEqualTo(1);
        assertThat(listener.succeeded).isEqualTo(1);
        // 增量档桶键钉死（双档分桶基线侧，与回填档守卫互为镜像；每可抽取 chunk 一次获取）
        verify(redissonClient, atLeastOnce()).getRateLimiter("rag:ratelimit:graph-extraction:t1");
    }

    @Test
    void backfillProfileUsesDedicatedBucketKey() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "企业")), List.of()));

        boolean ok = service.extract("t1", "d1", GraphExtractionService.RateProfile.BACKFILL);

        assertThat(ok).isTrue();
        // 回填档独立桶（免与增量档 setRate 同键互覆——同键异速率重置桶态）
        verify(redissonClient).getRateLimiter("rag:ratelimit:graph-extraction:backfill:t1");
        verify(redissonClient, never()).getRateLimiter("rag:ratelimit:graph-extraction:t1");
    }

    @Test
    void rateProfilesMapToDistinctRatesAndBuckets() {
        assertThat(service.rateFor(GraphExtractionService.RateProfile.INCREMENTAL))
            .as("增量档缺省 20 次/窗口").isEqualTo(20);
        assertThat(service.rateFor(GraphExtractionService.RateProfile.BACKFILL))
            .as("回填档缺省 60 次/窗口").isEqualTo(60);
        assertThat(service.bucketKey("t1", GraphExtractionService.RateProfile.INCREMENTAL))
            .isEqualTo("rag:ratelimit:graph-extraction:t1");
        assertThat(service.bucketKey("t1", GraphExtractionService.RateProfile.BACKFILL))
            .isEqualTo("rag:ratelimit:graph-extraction:backfill:t1");
    }

    @Test
    void duplicateEntityAcrossChunksConvergesToOneNode() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。"),
            chunk("c2", 1, "Alpha Corp 同时宣布了新的研发中心建设计划。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("alpha   CORP", "org", "企业")),
            List.of()));

        service.extract("t1", "d1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRecords.EntityWrite>> entities = ArgumentCaptor.forClass(List.class);
        verify(graphGateway).replaceDocumentGraph(eq("t1"), eq("d1"), any(), entities.capture(), any());
        assertThat(entities.getValue()).as("大小写/空白异写经确定性 ID 收敛单节点").hasSize(1);
        assertThat(entities.getValue().get(0).chunkIds()).containsExactly("c1", "c2");
    }

    @Test
    void tenantMismatchRejectedWithoutTouchingPipeline() {
        KbDocument doc = doc("other-tenant");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));

        assertThat(service.extract("t1", "d1")).isFalse();
        verifyNoInteractions(chunkRepository, entityExtractor, graphGateway);
    }

    @Test
    void documentNotFoundRejected() {
        when(documentRepository.findById("d1")).thenReturn(Optional.empty());

        assertThat(service.extract("t1", "d1")).isFalse();
        verifyNoInteractions(graphGateway);
    }

    @Test
    void embeddingDimensionMismatchFailsClosed() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "企业")), List.of()));
        when(embeddingModel.embedForResponse(any())).thenAnswer(inv ->
            batchResponse(inv.getArgument(0), 512));

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isFalse();
        assertThat(doc.getGraphStatus()).isEqualTo(GraphStatus.FAILED);
        assertThat(listener.failed).isEqualTo(1);
        verifyNoInteractions(graphGateway);
    }

    @Test
    void outOfScopeRelationDroppedButEntitiesKept() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "企业")),
            List.of(new ExtractionResult.RelationExtraction("Alpha Corp", "幽灵实体",
                "RELATED_TO", "越集关系应丢弃"))));

        service.extract("t1", "d1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GraphRecords.RelationWrite>> relations = ArgumentCaptor.forClass(List.class);
        verify(graphGateway).replaceDocumentGraph(eq("t1"), eq("d1"), any(), any(), relations.capture());
        assertThat(relations.getValue()).as("越集关系宁缺毋滥").isEmpty();
    }

    @Test
    void emptyCandidatesCompleteWithoutGraphWrite() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "过短")));

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isTrue();
        assertThat(doc.getGraphStatus()).isEqualTo(GraphStatus.COMPLETED);
        verifyNoInteractions(graphGateway, entityExtractor);
    }

    @Test
    void softDeletedChunksExcluded() {
        KbDocument doc = doc("t1");
        KbChunk deleted = chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。");
        deleted.setIsDeleted(true);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(deleted));

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isTrue();
        verifyNoInteractions(entityExtractor, graphGateway);
    }

    @Test
    void entitiesEmbeddedInBatchesOfTen() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "一篇涵盖十二项独立技术的综述材料，逐项陈述细节。")));
        List<ExtractionResult.EntityExtraction> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(new ExtractionResult.EntityExtraction("实体-" + i, "TECH", "技术描述 " + i));
        }
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(twelve, List.of()));

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isTrue();
        // 12 实体 = 10 条/批 ×2 次批量请求（逐条形态为 12 次往返，批量化实证提速项）
        verify(embeddingModel, times(2)).embedForResponse(any());
        verify(embeddingModel, never()).embed(anyString());
    }

    @Test
    void batchEmbeddingFailureFallsBackToPerEntityIsolation() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "企业")),
            List.of()));
        when(embeddingModel.embedForResponse(any())).thenThrow(new RuntimeException("批量端点故障"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).as("批失败回落逐条，单点隔离语义不退化").isTrue();
        assertThat(doc.getGraphStatus()).isEqualTo(GraphStatus.COMPLETED);
        verify(embeddingModel).embed(anyString());
    }

    @Test
    void malformedBatchResponseFallsBackToPerEntity() {
        KbDocument doc = doc("t1");
        when(documentRepository.findById("d1")).thenReturn(Optional.of(doc));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d1")).thenReturn(List.of(
            chunk("c1", 0, "Alpha Corp 发布了年度财报，营收增长显著。")));
        when(entityExtractor.extract(any(), anyString(), any())).thenReturn(result(
            List.of(new ExtractionResult.EntityExtraction("Alpha Corp", "ORG", "企业")),
            List.of()));
        // 条数与输入不符（供应商响应形态异常）→ 归位守卫拒绝 → 回落逐条
        when(embeddingModel.embedForResponse(any()))
            .thenReturn(new EmbeddingResponse(List.of()));
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);

        boolean ok = service.extract("t1", "d1");

        assertThat(ok).isTrue();
        assertThat(doc.getGraphStatus()).isEqualTo(GraphStatus.COMPLETED);
        verify(embeddingModel).embed(anyString());
    }
}
