package com.enterprise.kb.ai.retriever;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

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

    private VectorStore vectorStore;
    private ElasticsearchDocumentRetriever esRetriever;
    private HybridDocumentRetriever hybrid;

    private final Query query = new Query("增值税发票认证期限");

    private Document doc(String id) {
        return Document.builder().id(id).text("t-" + id)
            .metadata(Map.of("chunk_id", id)).build();
    }

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        esRetriever = mock(ElasticsearchDocumentRetriever.class);
        hybrid = new HybridDocumentRetriever(vectorStore, esRetriever, new RrfFusion());
    }

    @Test
    void retrieve_bothPathsOk_fusedResultsFromBoth() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
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
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
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
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenThrow(new RuntimeException("Milvus 连接失败"));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenReturn(List.of(doc("b1")));

        List<Document> result = assertDoesNotThrow(() -> hybrid.retrieve(query));

        assertEquals(1, result.size());
        assertEquals("b1", result.get(0).getId());
    }

    @Test
    void retrieve_bothPathsFail_returnsEmpty() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenThrow(new RuntimeException("vector down"));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenThrow(new RuntimeException("es down"));

        assertTrue(hybrid.retrieve(query).isEmpty());
    }

    /** 检索上下文经 Query.context 参数化传入：记录 vector 路 trace（bm25 路由 ES 检索器自记录） */
    @Test
    void retrieve_withContextInQuery_recordsVectorTrace() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        Query ctxQuery = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
            .thenReturn(List.of(doc("v1")));
        when(esRetriever.retrieve(any(Query.class), anyInt()))
            .thenReturn(List.of());

        hybrid.retrieve(ctxQuery);

        List<RetrievalContext.TraceEntry> trace = ctx.getTraceSummary();
        assertEquals(1, trace.size());
        assertEquals("vector", trace.get(0).source());
        assertEquals(List.of("v1"), trace.get(0).documents().stream().map(Document::getId).toList());
    }

    /**
     * fail-closed 防御纵深（3.9+3.10）：ctx 存在但租户身份缺失（身份异常态）→
     * 空结果且双路完全不触达，杜绝过滤缺失致跨租户全量可见。
     * 空证据由 ContextualQueryAugmenter 拒答模板承接（allowEmptyContext=false）。
     */
    @Test
    void retrieve_contextWithoutTenantId_failsClosedWithEmptyResult() {
        RetrievalContext ctx = new RetrievalContext();   // tenantId 未填充
        Query ctxQuery = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();

        assertTrue(hybrid.retrieve(ctxQuery).isEmpty());
        verifyNoInteractions(vectorStore);
        verifyNoInteractions(esRetriever);
    }
}
