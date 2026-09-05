package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * 混合检索器容错降级单测（mock 双路 + 真实 RrfFusion；簇④ 扩三路形态）
 */
class HybridDocumentRetrieverTest {

    private VectorStore vectorStore;
    private ElasticsearchDocumentRetriever esRetriever;
    private HybridDocumentRetriever hybrid;
    private RetrievalProperties properties;
    private AiBusinessMetrics metrics;

    private final Query query = new Query("增值税发票认证期限");

    private Document doc(String id) {
        return Document.builder().id(id).text("t-" + id)
            .metadata(Map.of("chunk_id", id)).build();
    }

    /** Graph 路 ObjectProvider 桩：缺位（关闭态）= getIfAvailable → null */
    @SuppressWarnings("unchecked")
    private ObjectProvider<GraphDocumentRetriever> graphProvider(GraphDocumentRetriever retriever) {
        ObjectProvider<GraphDocumentRetriever> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(retriever);
        return provider;
    }

    private void buildHybrid(GraphDocumentRetriever graphRetriever) {
        hybrid = new HybridDocumentRetriever(vectorStore, esRetriever, new RrfFusion(properties, metrics),
            metrics, properties,
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
            graphProvider(graphRetriever));
    }

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        esRetriever = mock(ElasticsearchDocumentRetriever.class);
        properties = new RetrievalProperties();
        metrics = new AiBusinessMetrics(new SimpleMeterRegistry());
        // 簇③ D2：执行器收编为构造注入（生产为共享 Bean hybridRetrievalExecutor）；
        // 簇④：Graph 路缺省缺位（关闭态双路形态逐字节不变）
        buildHybrid(null);
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

    /** 簇④ 三路形态：Graph 路在场时并入融合，命中携 graph_rank 元数据 + trace "graph" 条目 */
    @Test
    void retrieve_threeWayFusion_graphHitsMergedWithRankMetadata() {
        GraphDocumentRetriever graphRetriever = mock(GraphDocumentRetriever.class);
        buildHybrid(graphRetriever);
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        Query ctxQuery = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1")));
        when(esRetriever.retrieve(any(Query.class), anyInt())).thenReturn(List.of(doc("b1")));
        when(graphRetriever.retrieve(any(Query.class), anyInt()))
            .thenReturn(List.of(doc("g1"), doc("v1")));

        List<Document> result = hybrid.retrieve(ctxQuery);

        assertEquals(3, result.size());
        // v1 三路中占两路 → RRF 分最高
        assertEquals("v1", result.get(0).getId());
        Document g1 = result.stream().filter(d -> "g1".equals(d.getId())).findFirst().orElseThrow();
        assertTrue(g1.getMetadata().containsKey("graph_rank"));
        // trace 含 vector + graph 两条目（bm25 路由 ES 检索器自记录，mock 不写）
        List<String> sources = ctx.getTraceSummary().stream()
            .map(RetrievalContext.TraceEntry::source).toList();
        assertEquals(List.of("vector", "graph"), sources);
    }

    /** 簇④ 三路容错：Graph 路失败降级为空路，双路结果不受影响（降级矩阵三路扩展） */
    @Test
    void retrieve_graphPathFails_degradesToTwoWay() {
        GraphDocumentRetriever graphRetriever = mock(GraphDocumentRetriever.class);
        buildHybrid(graphRetriever);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1")));
        when(esRetriever.retrieve(any(Query.class), anyInt())).thenReturn(List.of(doc("b1")));
        when(graphRetriever.retrieve(any(Query.class), anyInt()))
            .thenThrow(new RuntimeException("Neo4j 不可达"));

        List<Document> result = assertDoesNotThrow(() -> hybrid.retrieve(query));

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(d -> d.getMetadata().containsKey("graph_rank")));
    }

    /** 簇④ 关闭态零回归：Graph 路缺位时融合面仅双路，无 graph_rank、无 graph trace */
    @Test
    void retrieve_graphAbsent_twoWayFormUnchanged() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        Query ctxQuery = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc("v1")));
        when(esRetriever.retrieve(any(Query.class), anyInt())).thenReturn(List.of(doc("b1")));

        List<Document> result = hybrid.retrieve(ctxQuery);

        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(d -> d.getMetadata().containsKey("graph_rank")));
        assertTrue(ctx.getTraceSummary().stream()
            .noneMatch(e -> "graph".equals(e.source())));
    }

    /**
     * 坑位㊺ 契约钉死：executor 注入点显式 @Qualifier——容器内 ExecutorService Bean
     * 不唯一（编排开关开启态另有 orchestratorSubAgentExecutor），限定符缺失时
     * 按类型歧义启动失败（IDEA 编译无 -parameters 时按名消歧亦失效）。
     */
    @Test
    void executorInjectionPointPinnedByQualifier() {
        var ctor = HybridDocumentRetriever.class.getDeclaredConstructors()[0];
        var param = java.util.Arrays.stream(ctor.getParameters())
            .filter(p -> p.getType() == java.util.concurrent.ExecutorService.class)
            .findFirst().orElseThrow();
        var qualifier = param.getAnnotation(
            org.springframework.beans.factory.annotation.Qualifier.class);
        assertNotNull(qualifier, "executor 注入点必须显式 @Qualifier（坑位㊺）");
        assertEquals("hybridRetrievalExecutor", qualifier.value());
    }
}
