package com.enterprise.kb.ai.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.enterprise.kb.infrastructure.elasticsearch.EsChunkDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ES BM25 检索路（设计文档 10.3）—— ik 双模式分词（ik_max_word 索引 / ik_smart 检索）
 *
 * <p>作为 HybridDocumentRetriever（2.7）的组件使用，非 Advisor 链直接挂载。
 * 返回的 Document 携带 BM25 得分/排名元数据（10.1 元数据约定），
 * Document.id = chunk_id（RRF 融合键）。
 *
 * <p>租户过滤：请求上下文中 tenantId 存在时拼 term 过滤；非 Web 上下文
 * （如 kb-eval 评估）无 RetrievalContext 实例，降级为不过滤（评估期可接受，
 * 生产链路由 2.11 RetrievalTraceAdvisor 保证 tenantId 必填充）。
 */
@Slf4j
@Component
public class ElasticsearchDocumentRetriever {

    private final ElasticsearchClient esClient;
    private final ObjectProvider<RetrievalContext> retrievalContextProvider;

    public ElasticsearchDocumentRetriever(ElasticsearchClient esClient,
                                          ObjectProvider<RetrievalContext> retrievalContextProvider) {
        this.esClient = esClient;
        this.retrievalContextProvider = retrievalContextProvider;
    }

    public List<Document> retrieve(Query query, int size) {
        String tenantId = currentTenantId();
        try {
            SearchResponse<EsChunkDoc> response = esClient.search(s -> s
                .index(EsChunkDoc.INDEX)
                .query(q -> q.bool(b -> {
                    b.must(m -> m.match(mm -> mm.field("content").query(query.text())));
                    if (tenantId != null) {
                        b.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
                    }
                    b.filter(f -> f.term(t -> t.field("is_deleted").value(false)));
                    return b;
                }))
                .size(size), EsChunkDoc.class);

            List<Hit<EsChunkDoc>> hits = response.hits().hits();
            List<Document> docs = new ArrayList<>(hits.size());
            for (int i = 0; i < hits.size(); i++) {
                docs.add(toDocument(hits.get(i), i + 1));
            }

            // 溯源 trace 记录（2.11/2.12 SSE TRACE 数据源；非 Web 上下文降级跳过）
            RetrievalContext ctx = requestContext();
            if (ctx != null) {
                ctx.addTraceEntry("bm25", docs);
            }
            return docs;
        } catch (IOException e) {
            // 容错交由 HybridDocumentRetriever（2.7 单路失败降级）：此处如实上抛
            throw new ElasticsearchRetrievalException("ES BM25 检索失败: " + e.getMessage(), e);
        }
    }

    /** Hit → Document 映射（包内可见，供单元测试覆盖） */
    Document toDocument(Hit<EsChunkDoc> hit, int rank) {
        EsChunkDoc src = hit.source();
        // 注意：Spring AI Document metadata 禁止 null 值，可空字段仅在非空时写入
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunk_id", src.getChunkId());
        meta.put("doc_id", src.getDocId());
        meta.put("tenant_id", src.getTenantId());
        meta.put("chunk_type", src.getChunkType() != null ? src.getChunkType() : "TEXT");
        if (src.getFileName() != null) meta.put("file_name", src.getFileName());
        if (src.getPageNum() != null) meta.put("page_num", src.getPageNum());
        meta.put("bm25_score", hit.score());
        meta.put("bm25_rank", rank);
        meta.put("retrieval_source", "bm25");
        return Document.builder()
            .id(src.getChunkId())      // 融合键 = kb_chunk.id
            .text(src.getContent())
            .metadata(meta)
            .score(hit.score())
            .build();
    }

    /** 请求上下文安全访问：非 Web 上下文返回 null（评估/异步场景降级） */
    private RetrievalContext requestContext() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        try {
            return retrievalContextProvider.getObject();
        } catch (Exception e) {
            log.debug("RetrievalContext 获取失败，降级为无上下文: {}", e.getMessage());
            return null;
        }
    }

    private String currentTenantId() {
        RetrievalContext ctx = requestContext();
        return ctx != null ? ctx.getTenantId() : null;
    }

    /** ES 检索异常包装，供 2.7 HybridDocumentRetriever 识别单路失败 */
    public static class ElasticsearchRetrievalException extends RuntimeException {
        public ElasticsearchRetrievalException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
