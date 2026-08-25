package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 混合检索器（设计文档 10.2）—— 双路并行召回 + RRF 融合
 *
 * <p>实现 Spring AI {@link DocumentRetriever}，作为 RetrievalAugmentationAdvisor（2.10）
 * 的 documentRetriever 挂载点。内部编排：
 * <ol>
 *   <li>虚拟线程（Java 21 正式 API）并行执行向量路（直连 {@link VectorStore}）
 *       与 BM25 路（{@link ElasticsearchDocumentRetriever}），单路超时降级（10.8）</li>
 *   <li>单路容错：任一路失败/超时返回空列表并告警，不拖垮整体（降级矩阵见 10.2）</li>
 *   <li>{@link RrfFusion} 融合双路排名，输出 recallSize（topK×倍数）条带溯源元数据的结果</li>
 * </ol>
 *
 * <p>调优参数（topK/召回倍数/相似度阈值/单路超时）经 {@link RetrievalProperties}
 * （rag.retrieval.*）注入，默认值 = Phase 2 基线形态，调参须配 kb-eval 基线对比。
 *
 * <p>双路并行执行器为共享 Bean {@code hybridRetrievalExecutor}（RetrievalConfig，
 * v2.19 簇③ D2）——此前每请求 new 虚拟线程 executor，高频请求下重复创建/关闭。
 *
 * <p>租户/软删过滤与 trace 的 {@link RetrievalContext} 经 Query.context 参数化传入
 * （2026-08-02 重构：取代请求作用域代理——MVC 异步请求完结后作用域不可解析，
 * 流式路径过滤与 trace 曾静默失效）。向量路在此直连 VectorStore 构建 SearchRequest
 * 以携带按请求的过滤表达式（VectorStoreDocumentRetriever 的 filterExpression supplier
 * 无 Query 入口，无法参数化）。
 *
 * <p>重排序（2.9 qwen3-rerank）不在本组件内——以 DocumentPostProcessor 形态
 * 挂在 Advisor 链上（10.5/10.6），职责分离。
 */
@Slf4j
@Component
public class HybridDocumentRetriever implements DocumentRetriever {

    private final VectorStore vectorStore;
    private final ElasticsearchDocumentRetriever esRetriever;
    private final RrfFusion rrfFusion;
    private final AiBusinessMetrics metrics;
    /** 检索调优参数（rag.retrieval.*，默认值 = Phase 2 基线形态） */
    private final RetrievalProperties properties;
    /** 双路并行共享执行器（v2.19 簇③ D2 收编为 Bean，取代每请求 new） */
    private final ExecutorService executor;
    /**
     * Graph 路检索器（簇④ 5.2，三路融合第三路）：{@code rag.graph.enabled=true}
     * 条件装配——关闭态 Bean 缺位，ObjectProvider 容忍，双路形态逐字节不变。
     */
    private final ObjectProvider<GraphDocumentRetriever> graphRetrieverProvider;

    public HybridDocumentRetriever(VectorStore vectorStore,
                                   ElasticsearchDocumentRetriever esRetriever,
                                   RrfFusion rrfFusion,
                                   AiBusinessMetrics metrics,
                                   RetrievalProperties properties,
                                   ExecutorService executor,
                                   ObjectProvider<GraphDocumentRetriever> graphRetrieverProvider) {
        this.vectorStore = vectorStore;
        this.esRetriever = esRetriever;
        this.rrfFusion = rrfFusion;
        this.metrics = metrics;
        this.properties = properties;
        this.executor = executor;
        this.graphRetrieverProvider = graphRetrieverProvider;
    }

    @Override
    public List<Document> retrieve(Query query) {
        int recallSize = properties.getTopK() * properties.getRecallMultiplier();
        RetrievalContext ctx = RetrievalContext.from(query);
        // fail-closed 防御纵深（3.9+3.10 安全收敛）：Web 入口已保证 ctx 存在且 tenantId
        // 非空（AgentController 身份守卫）；若出现「有 ctx 无租户」的身份异常态，宁可
        // 返回空证据走拒答路径，绝不允许过滤缺失致跨租户全量可见。
        // kb-eval 等非 Web 入口（ctx==null）保持无过滤的评估语义，不受影响。
        if (ctx != null && (ctx.getTenantId() == null || ctx.getTenantId().isBlank())) {
            log.warn("检索上下文缺失租户身份，fail-closed 返回空结果（拒绝跨租户风险）");
            return List.of();
        }
        long start = System.currentTimeMillis();

        // 虚拟线程并行多路召回；各路各自容错（失败/超时 → 空列表，降级矩阵 10.2 三路扩展）
        // 共享执行器（簇③ D2）：等待与超时语义不变——await 阻塞 + 超时取消。
        // Graph 路（簇④ 5.2）条件在场：关闭态 provider 空 → 双路形态逐字节不变
        GraphDocumentRetriever graphRetriever = graphRetrieverProvider.getIfAvailable();
        List<Document> vectorHits;
        List<Document> bm25Hits;
        List<Document> graphHits = List.of();
        long[] vectorLatency = new long[1];
        long[] graphLatency = new long[1];
        Future<List<Document>> vectorFuture = executor.submit(
            () -> retrieveSafely(() -> {
                long t0 = System.currentTimeMillis();
                List<Document> hits = vectorSearch(query, recallSize, ctx);
                vectorLatency[0] = System.currentTimeMillis() - t0;
                return hits;
            }, "vector"));
        Future<List<Document>> bm25Future = executor.submit(
            () -> retrieveSafely(() -> esRetriever.retrieve(query, recallSize), "bm25"));
        Future<List<Document>> graphFuture = graphRetriever == null ? null : executor.submit(
            () -> retrieveSafely(() -> {
                long t0 = System.currentTimeMillis();
                List<Document> hits = graphRetriever.retrieve(query, recallSize);
                graphLatency[0] = System.currentTimeMillis() - t0;
                return hits;
            }, "graph"));
        vectorHits = await(vectorFuture, "vector");
        bm25Hits = await(bm25Future, "bm25");
        if (graphFuture != null) {
            graphHits = await(graphFuture, "graph");
        }

        // 向量路/图路 trace（bm25 路由 ES 检索器自记录；Future.get 建立 happens-before，
        // 此刻写入均已可见，CopyOnWriteArrayList 保证快照读安全）
        if (ctx != null) {
            ctx.addTraceEntry("vector", vectorHits, vectorLatency[0]);
            if (graphFuture != null) {
                ctx.addTraceEntry("graph", graphHits, graphLatency[0]);
            }
        }

        // N 路 RRF 融合（簇④ 5.2）：graph 路仅在场时入融合面；关闭态双路键序不变
        Map<String, List<Document>> routeHits = new LinkedHashMap<>();
        routeHits.put("vector", vectorHits);
        routeHits.put("bm25", bm25Hits);
        if (graphFuture != null) {
            routeHits.put("graph", graphHits);
        }
        List<Document> fused = rrfFusion.fuse(routeHits, recallSize);
        long elapsed = System.currentTimeMillis() - start;
        // 检索延迟 Timer（rag.retrieval.latency，3.13）：真实耗时，Prometheus 侧出分位
        metrics.recordRetrievalLatency(Duration.ofMillis(elapsed));
        log.debug("混合检索完成: vector={} bm25={} graph={} fused={} 耗时={}ms",
            vectorHits.size(), bm25Hits.size(),
            graphFuture == null ? "off" : graphHits.size(), fused.size(), elapsed);
        return fused;
    }

    /** 向量路：直连 VectorStore，按请求上下文携带租户/软删过滤表达式 */
    private List<Document> vectorSearch(Query query, int recallSize, RetrievalContext ctx) {
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query.text())
            .topK(recallSize)
            .similarityThreshold(properties.getSimilarityThreshold());
        if (ctx != null && ctx.getSecurityFilter() != null) {
            builder.filterExpression(ctx.getSecurityFilter());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /** 单路容错：失败不扩散，返回空列表（降级矩阵 10.2：双路全空时由空证据路径兜底） */
    private List<Document> retrieveSafely(Supplier<List<Document>> call, String route) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("检索路径 [{}] 失败，降级为空结果: {}", route, e.getMessage());
            return List.of();
        }
    }

    /** 等待单路结果：超时即取消并降级为空，不阻塞另一路 */
    private List<Document> await(Future<List<Document>> future, String route) {
        int timeoutSeconds = properties.getPathTimeoutSeconds();
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("检索路径 [{}] 超时（{}s），降级为空结果", route, timeoutSeconds);
            future.cancel(true);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.warn("检索路径 [{}] 异常，降级为空结果: {}", route, e.getMessage());
            return List.of();
        }
    }
}
