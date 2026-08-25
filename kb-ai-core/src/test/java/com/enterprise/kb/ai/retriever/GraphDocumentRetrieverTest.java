package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.GraphRetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Graph 路检索器单测（簇④ 5.2）：管线映射 + 租户 fail-closed +
 * PG 纵深校验（越租户/软删丢弃）+ 指标计数。
 */
class GraphDocumentRetrieverTest {

    private static final String TENANT = "tenant-a";

    private GraphGateway graphGateway;
    private EmbeddingModel embeddingModel;
    private KbChunkRepository chunkRepository;
    private KbDocumentRepository documentRepository;
    private SimpleMeterRegistry registry;
    private GraphDocumentRetriever retriever;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        graphGateway = mock(GraphGateway.class);
        embeddingModel = mock(EmbeddingModel.class);
        chunkRepository = mock(KbChunkRepository.class);
        documentRepository = mock(KbDocumentRepository.class);
        registry = new SimpleMeterRegistry();
        AiBusinessMetrics metrics = new AiBusinessMetrics(registry);
        ObjectProvider<ObservationRegistry> observationProvider = mock(ObjectProvider.class);
        when(observationProvider.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        retriever = new GraphDocumentRetriever(graphGateway, embeddingModel,
            chunkRepository, documentRepository, new GraphRetrievalProperties(),
            metrics, observationProvider);
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
    }

    private Query queryWithTenant(String tenantId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        return Query.builder().text("A 公司的 CTO 曾任教于哪所大学")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();
    }

    private KbChunk chunk(String id, String docId, boolean deleted) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setDocId(docId);
        chunk.setChunkIndex(0);
        chunk.setContent("内容-" + id);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setIsDeleted(deleted);
        return chunk;
    }

    private KbDocument doc(String id, String tenantId) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setTenantId(tenantId);
        doc.setName("手册.pdf");
        return doc;
    }

    private GraphRecords.GraphChunkHit hit(String chunkId, String docId, double score) {
        return new GraphRecords.GraphChunkHit(chunkId, docId, score, List.of("A公司", "张工"), 0);
    }

    @Test
    void retrieve_mapsHitsToDocumentsWithGraphMetadata() {
        when(graphGateway.retrieveChunks(eq(TENANT), any(), anyInt(), anyDouble(), anyBoolean(), anyInt()))
            .thenReturn(List.of(hit("c1", "d1", 0.9), hit("c2", "d1", 0.7)));
        when(chunkRepository.findAllById(any())).thenReturn(List.of(chunk("c1", "d1", false), chunk("c2", "d1", false)));
        when(documentRepository.findAllById(any())).thenReturn(List.of(doc("d1", TENANT)));

        List<Document> documents = retriever.retrieve(queryWithTenant(TENANT), 10);

        assertEquals(2, documents.size());
        Document first = documents.get(0);
        assertEquals("c1", first.getId());
        assertEquals(1, first.getMetadata().get("graph_rank"));
        assertEquals(0.9, (Double) first.getMetadata().get("graph_score"), 1e-12);
        assertEquals("A公司，张工", first.getMetadata().get("graph_entity_hits"));
        assertEquals("手册.pdf", first.getMetadata().get("file_name"));
        assertEquals("graph", first.getMetadata().get("retrieval_source"));
        assertEquals(1.0, registry.counter("rag.retrieval.graph.total").count());
        assertEquals(1.0, registry.counter("rag.retrieval.graph.hit").count());
    }

    @Test
    void retrieve_withoutTenantFailsClosedZeroTouch() {
        RetrievalContext ctx = new RetrievalContext();   // tenantId 未填充
        Query query = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();

        assertTrue(retriever.retrieve(query, 10).isEmpty());
        verifyNoInteractions(graphGateway, embeddingModel);
    }

    @Test
    void retrieve_crossTenantDocFromGraphDroppedAsDefenseInDepth() {
        // 图数据错写场景：网关返回的 docId 归属他租户 → PG 纵深校验丢弃
        when(graphGateway.retrieveChunks(eq(TENANT), any(), anyInt(), anyDouble(), anyBoolean(), anyInt()))
            .thenReturn(List.of(hit("c1", "d-evil", 0.9), hit("c2", "d1", 0.8)));
        when(chunkRepository.findAllById(any()))
            .thenReturn(List.of(chunk("c1", "d-evil", false), chunk("c2", "d1", false)));
        when(documentRepository.findAllById(any()))
            .thenReturn(List.of(doc("d-evil", "other-tenant"), doc("d1", TENANT)));

        List<Document> documents = retriever.retrieve(queryWithTenant(TENANT), 10);

        assertEquals(1, documents.size());
        assertEquals("c2", documents.get(0).getId());
    }

    @Test
    void retrieve_softDeletedChunkDropped() {
        when(graphGateway.retrieveChunks(eq(TENANT), any(), anyInt(), anyDouble(), anyBoolean(), anyInt()))
            .thenReturn(List.of(hit("c1", "d1", 0.9)));
        when(chunkRepository.findAllById(any())).thenReturn(List.of(chunk("c1", "d1", true)));
        when(documentRepository.findAllById(any())).thenReturn(List.of(doc("d1", TENANT)));

        assertTrue(retriever.retrieve(queryWithTenant(TENANT), 10).isEmpty());
    }

    @Test
    void retrieve_emptyGraphResultCountsMiss() {
        when(graphGateway.retrieveChunks(eq(TENANT), any(), anyInt(), anyDouble(), anyBoolean(), anyInt()))
            .thenReturn(List.of());

        assertTrue(retriever.retrieve(queryWithTenant(TENANT), 10).isEmpty());
        assertEquals(1.0, registry.counter("rag.retrieval.graph.total").count());
        assertEquals(0.0, registry.counter("rag.retrieval.graph.hit").count());
    }
}
