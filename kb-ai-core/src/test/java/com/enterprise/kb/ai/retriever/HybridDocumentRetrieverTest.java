package com.enterprise.kb.ai.retriever;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 混合检索器容错降级单测（mock 双路 + 真实 RrfFusion）
 */
class HybridDocumentRetrieverTest {

    private VectorStoreDocumentRetriever vectorRetriever;
    private ElasticsearchDocumentRetriever esRetriever;
    private HybridDocumentRetriever hybrid;

    private final Query query = new Query("增值税发票认证期限");

    private Document doc(String id) {
        return Document.builder().id(id).text("t-" + id)
            .metadata(Map.of("chunk_id", id)).build();
    }

    @BeforeEach
    void setUp() {
        vectorRetriever = mock(VectorStoreDocumentRetriever.class);
        esRetriever = mock(ElasticsearchDocumentRetriever.class);
        hybrid = new HybridDocumentRetriever(vectorRetriever, esRetriever, new RrfFusion());
    }

    @Test
    void retrieve_bothPathsOk_fusedResultsFromBoth() {
        when(vectorRetriever.retrieve(any(Query.class)))
            .thenReturn(List.of(doc("v1"), doc("shared")));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenReturn(List.of(doc("b1"), doc("shared")));

        List<Document> result = hybrid.retrieve(query);

        assertFalse(result.isEmpty());
        // 双路命中的 shared 排最前（双路 RRF 分 > 单路）
        assertEquals("shared", result.get(0).getId());
        assertEquals(3, result.size());
    }

    @Test
    void retrieve_esPathFails_degradesToVectorOnly() {
        when(vectorRetriever.retrieve(any(Query.class)))
            .thenReturn(List.of(doc("v1"), doc("v2")));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenThrow(new ElasticsearchDocumentRetriever
                .ElasticsearchRetrievalException("ES 不可达", new java.io.IOException("timeout")));

        List<Document> result = assertDoesNotThrow(() -> hybrid.retrieve(query));

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(d -> d.getMetadata().containsKey("vector_rank")));
        assertTrue(result.stream().noneMatch(d -> d.getMetadata().containsKey("bm25_rank")));
    }

    @Test
    void retrieve_vectorPathFails_degradesToBm25Only() {
        when(vectorRetriever.retrieve(any(Query.class)))
            .thenThrow(new RuntimeException("Milvus 连接失败"));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenReturn(List.of(doc("b1")));

        List<Document> result = assertDoesNotThrow(() -> hybrid.retrieve(query));

        assertEquals(1, result.size());
        assertEquals("b1", result.get(0).getId());
    }

    @Test
    void retrieve_bothPathsFail_returnsEmpty() {
        when(vectorRetriever.retrieve(any(Query.class)))
            .thenThrow(new RuntimeException("vector down"));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenThrow(new RuntimeException("es down"));

        assertTrue(hybrid.retrieve(query).isEmpty());
    }
}
