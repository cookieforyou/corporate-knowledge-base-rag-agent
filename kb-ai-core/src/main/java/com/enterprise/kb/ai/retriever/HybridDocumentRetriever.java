package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.commons.constant.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 混合检索器（设计文档 10.2）—— 双路并行召回 + RRF 融合
 *
 * <p>实现 Spring AI {@link DocumentRetriever}，作为 RetrievalAugmentationAdvisor（2.10）
 * 的 documentRetriever 挂载点。内部编排：
 * <ol>
 *   <li>虚拟线程（Java 21 正式 API）并行执行向量路（直连 {@link VectorStore}）
 *       与 BM25 路（{@link ElasticsearchDocumentRetriever}），每路 5s 超时（10.8）</li>
 *   <li>单路容错：任一路失败/超时返回空列表并告警，不拖垮整体（降级矩阵见 10.2）</li>
 *   <li>{@link RrfFusion} 融合双路排名，输出 recallSize（topK×2）条带溯源元数据的结果</li>
 * </ol>
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

    /** 召回放大系数：recallSize = topK × 2，给融合与重排留余量 */
    private static final int RECALL_MULTIPLIER = 2;
    /** 单路检索超时（秒）：超时即降级为空，不阻塞（10.8） */
    private static final int PATH_TIMEOUT_SECONDS = 5;
    /** 向量相似度阈值（与 Phase 1 QuestionAnswerAdvisor 基线一致） */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;
    private final ElasticsearchDocumentRetriever esRetriever;
    private final RrfFusion rrfFusion;

    public HybridDocumentRetriever(VectorStore vectorStore,
                                   ElasticsearchDocumentRetriever esRetriever,
                                   RrfFusion rrfFusion) {
        this.vectorStore = vectorStore;
        this.esRetriever = esRetriever;
        this.rrfFusion = rrfFusion;
    }

    @Override
    public List<Document> retrieve(Query query) {
        int recallSize = Constants.DEFAULT_TOP_K * RECALL_MULTIPLIER;
        RetrievalContext ctx = RetrievalContext.from(query);
        long start = System.currentTimeMillis();

        // 虚拟线程并行双路召回；两路各自容错（失败/超时 → 空列表，降级矩阵 10.2）
        List<Document> vectorHits;
        List<Document> bm25Hits;
        long[] vectorLatency = new long[1];
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Document>> vectorFuture = executor.submit(
                () -> retrieveSafely(() -> {
                    long t0 = System.currentTimeMillis();
                    List<Document> hits = vectorSearch(query, recallSize, ctx);
                    vectorLatency[0] = System.currentTimeMillis() - t0;
                    return hits;
                }, "vector"));
            Future<List<Document>> bm25Future = executor.submit(
                () -> retrieveSafely(() -> esRetriever.retrieve(query, recallSize), "bm25"));
            vectorHits = await(vectorFuture, "vector");
            bm25Hits = await(bm25Future, "bm25");
        }

        // 向量路 trace（bm25 路由 ES 检索器自记录；Future.get 建立 happens-before，
        // 此刻 ES 路写入与 vectorLatency 赋值均已可见，CopyOnWriteArrayList 保证快照读安全）
        if (ctx != null) {
            ctx.addTraceEntry("vector", vectorHits, vectorLatency[0]);
        }

        List<Document> fused = rrfFusion.fuse(vectorHits, bm25Hits, recallSize);
        log.debug("混合检索完成: vector={} bm25={} fused={} 耗时={}ms",
            vectorHits.size(), bm25Hits.size(), fused.size(), System.currentTimeMillis() - start);
        return fused;
    }

    /** 向量路：直连 VectorStore，按请求上下文携带租户/软删过滤表达式 */
    private List<Document> vectorSearch(Query query, int recallSize, RetrievalContext ctx) {
        SearchRequest.Builder builder = SearchRequest.builder()
            .query(query.text())
            .topK(recallSize)
            .similarityThreshold(SIMILARITY_THRESHOLD);
        if (ctx != null && ctx.getSecurityFilter() != null) {
            builder.filterExpression(ctx.getSecurityFilter());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /** 单路容错：失败不扩散，返回空列表（降级矩阵 10.2：双路全空时由空证据路径兜底） */
    private List<Document> retrieveSafely(java.util.function.Supplier<List<Document>> call, String route) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("检索路径 [{}] 失败，降级为空结果: {}", route, e.getMessage());
            return List.of();
        }
    }

    /** 等待单路结果：超时即取消并降级为空，不阻塞另一路 */
    private List<Document> await(Future<List<Document>> future, String route) {
        try {
            return future.get(PATH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("检索路径 [{}] 超时（{}s），降级为空结果", route, PATH_TIMEOUT_SECONDS);
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
