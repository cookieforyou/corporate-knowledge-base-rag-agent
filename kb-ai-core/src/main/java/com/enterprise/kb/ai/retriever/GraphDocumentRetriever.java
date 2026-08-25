package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.GraphRetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph 路检索器（簇④ 5.2，三路融合第三路）。
 *
 * <p>管线（<b>检索期零 LLM 调用</b>，延迟预算 ~100ms）：
 * 查询嵌入（主检索链路同源 EmbeddingModel）→ Neo4j 向量索引实体匹配
 * （租户过滤 + 相似度阈值）→ 可选 1 跳邻域展开（衰减 0.5）→ MENTIONS
 * 反查存活 chunk 锚点 → <b>PG 事实源反查内容</b>（图内不存内容）+
 * 租户纵深校验。
 *
 * <p>租户隔离两层纪律沿用：无上下文/无租户 → 空列表零触达（fail-closed）；
 * PG 反查侧对文档归属做纵深校验（图侧已按租户过滤，此处防图数据错写扩散）。
 *
 * <p>容错：异常上抛由 {@link HybridDocumentRetriever} 单路容错统一降级
 * （降级矩阵三路形态：任一路失败/超时 → 空路，不拖垮整体）。
 *
 * <p>条件装配：{@code rag.graph.enabled=true} 才由 RetrievalConfig 装配本 Bean；
 * 关闭态 Bean 缺位，{@code ObjectProvider} 消费侧零触达（链形态零变化纪律）。
 */
@Slf4j
public class GraphDocumentRetriever {

    private final GraphGateway graphGateway;
    private final EmbeddingModel embeddingModel;
    private final KbChunkRepository chunkRepository;
    private final KbDocumentRepository documentRepository;
    private final GraphRetrievalProperties properties;
    private final AiBusinessMetrics metrics;
    private final ObservationRegistry observationRegistry;

    public GraphDocumentRetriever(GraphGateway graphGateway,
                                  EmbeddingModel embeddingModel,
                                  KbChunkRepository chunkRepository,
                                  KbDocumentRepository documentRepository,
                                  GraphRetrievalProperties properties,
                                  AiBusinessMetrics metrics,
                                  ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.graphGateway = graphGateway;
        this.embeddingModel = embeddingModel;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    /**
     * Graph 路召回。
     *
     * @param recallSize 召回上限（与双路同口径 = topK × recallMultiplier）
     */
    public List<Document> retrieve(Query query, int recallSize) {
        RetrievalContext ctx = RetrievalContext.from(query);
        String tenantId = ctx == null ? null : ctx.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();   // fail-closed：无租户零触达（网关侧同守卫，双保险）
        }
        long start = System.currentTimeMillis();
        try {
            List<Document> documents = Observation
                .createNotStarted("kb.retrieval.graph", observationRegistry)
                .observeChecked(() -> doRetrieve(query.text(), tenantId, recallSize));
            metrics.recordGraphRetrieval(!documents.isEmpty());
            return documents;
        } finally {
            metrics.recordGraphRetrievalLatency(Duration.ofMillis(System.currentTimeMillis() - start));
        }
    }

    private List<Document> doRetrieve(String queryText, String tenantId, int recallSize) {
        float[] queryEmbedding = embeddingModel.embed(queryText);
        List<GraphRecords.GraphChunkHit> hits = graphGateway.retrieveChunks(
            tenantId, queryEmbedding,
            properties.getEntityTopN(), properties.getEntitySimilarityThreshold(),
            properties.isExpandNeighbors(), recallSize);
        if (hits.isEmpty()) {
            return List.of();
        }
        return toDocuments(hits, tenantId);
    }

    /** chunk 反查 PG 事实源（图内不存内容）+ 租户纵深校验 + Document 映射 */
    private List<Document> toDocuments(List<GraphRecords.GraphChunkHit> hits, String tenantId) {
        List<String> chunkIds = hits.stream().map(GraphRecords.GraphChunkHit::chunkId).toList();
        Map<String, KbChunk> chunksById = new HashMap<>();
        chunkRepository.findAllById(chunkIds).forEach(c -> chunksById.put(c.getId(), c));

        // 文档归属纵深校验：chunk 表无租户列，经文档租户过滤（防图数据错写扩散）
        Set<String> docIds = new HashSet<>();
        hits.forEach(h -> docIds.add(h.docId()));
        Map<String, KbDocument> docsById = new HashMap<>();
        documentRepository.findAllById(docIds).forEach(d -> docsById.put(d.getId(), d));

        List<Document> documents = new ArrayList<>(hits.size());
        int rank = 0;
        for (GraphRecords.GraphChunkHit hit : hits) {
            KbChunk chunk = chunksById.get(hit.chunkId());
            KbDocument doc = docsById.get(hit.docId());
            if (chunk == null || Boolean.TRUE.equals(chunk.getIsDeleted())) {
                continue;   // 图锚点与 PG 生命周期竞态（删除在途）：保守丢弃
            }
            if (doc == null || !tenantId.equals(doc.getTenantId())) {
                log.warn("图路命中越租户/失主文档，纵深丢弃: chunkId={}, docId={}",
                    hit.chunkId(), hit.docId());
                continue;
            }
            rank++;
            documents.add(toDocument(chunk, doc, hit, rank));
        }
        return documents;
    }

    /** 元数据契约与双路同键族（调试台/审计/溯源消费面零分叉） */
    private Document toDocument(KbChunk chunk, KbDocument doc, GraphRecords.GraphChunkHit hit, int rank) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("chunk_id", chunk.getId());
        meta.put("doc_id", doc.getId());
        meta.put("tenant_id", doc.getTenantId());
        meta.put("chunk_type", chunk.getChunkType() != null ? chunk.getChunkType().name() : "TEXT");
        if (doc.getName() != null) {
            meta.put("file_name", doc.getName());
        }
        if (chunk.getPageNum() != null) {
            meta.put("page_num", chunk.getPageNum());
        }
        meta.put("graph_score", hit.score());
        meta.put("graph_rank", rank);
        meta.put("graph_hop", hit.hop());
        meta.put("graph_entity_hits", String.join("，", hit.entityNames()));
        meta.put("retrieval_source", "graph");
        return Document.builder()
            .id(chunk.getId())
            .text(chunk.getContent())
            .metadata(meta)
            .score(hit.score())
            .build();
    }
}
