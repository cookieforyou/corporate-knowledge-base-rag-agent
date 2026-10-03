package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.GraphRetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.domain.enums.ChunkType;
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
 * Graph 路检索器（Phase5簇④ 5.2，三路融合第三路）。
 *
 * <p>管线（<b>检索期零 LLM 调用</b>，延迟预算 ~100ms）：
 * 查询嵌入（主检索链路同源 EmbeddingModel）→ Neo4j 向量索引实体匹配
 * （<b>索引过取</b> → 租户过滤 + 相似度阈值 → 种子封顶，v2.85 跨租户饿死修复）
 * → 可选 1 跳邻域展开（衰减 0.5）→ MENTIONS
 * 反查存活 chunk 锚点 → <b>PG 事实源反查内容</b>（图内不存内容）+
 * 租户纵深校验。空召回时追加一次归因读数（seed_starved / anchor_gap 指标，
 * 命中路径零成本）。
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

    /**
     * 归因放大窗口倍数/下限（v3.03 修正 F3）：饿死判据 = 本租户阈值内实体在
     * {@code fetchLimit × 本倍数}（不低于下限）的窗口内可见，却挤不进检索窗口。
     * 倍数须足够大以容纳「他租户密集占位」的真实场景，下限兜住小 fetchLimit 情形。
     */
    static final int DIAGNOSTIC_WINDOW_MULTIPLIER = 10;
    static final int DIAGNOSTIC_WINDOW_MIN = 200;

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
        Retrieval retrieval;
        try {
            retrieval = Observation
                .createNotStarted("kb.retrieval.graph", observationRegistry)
                .observeChecked(() -> doRetrieve(query.text(), tenantId, recallSize));
            metrics.recordGraphRetrieval(!retrieval.documents().isEmpty());
        } finally {
            metrics.recordGraphRetrievalLatency(Duration.ofMillis(System.currentTimeMillis() - start));
        }
        // 归因触发门 = 最终 documents 为空（v3.03 复核修正 F10：与命中指标同门）。
        // 原以「网关 hits 为空」触发——hits 非空但被 PG 纵深校验全丢（锚点/PG 漂移）时，
        // 图路实际零贡献却无任何归因读数（仅余一条 WARN 日志）。归因在计时窗口之外，
        // 不污染图路延迟读数。
        if (retrieval.documents().isEmpty()) {
            diagnoseEmptyRetrieval(tenantId, retrieval.queryEmbedding(), retrieval.fetchLimit());
        }
        return retrieval.documents();
    }

    /** doRetrieve 产物：文档 + 归因读数材料（复用查询向量，避免为空路径重复嵌入） */
    private record Retrieval(List<Document> documents, float[] queryEmbedding, int fetchLimit) {
    }

    private Retrieval doRetrieve(String queryText, String tenantId, int recallSize) {
        float[] queryEmbedding = embeddingModel.embed(queryText);
        int seedLimit = properties.getEntityTopN();
        int fetchLimit = fetchLimit(seedLimit);
        List<GraphRecords.GraphChunkHit> hits = graphGateway.retrieveChunks(tenantId,
            new GraphRecords.GraphRetrievalSpec(queryEmbedding, seedLimit, fetchLimit,
                properties.getEntitySimilarityThreshold(), expandDirection(), candidateLimit(), recallSize));
        if (hits.isEmpty()) {
            return new Retrieval(List.of(), queryEmbedding, fetchLimit);
        }
        return new Retrieval(toDocuments(hits, tenantId), queryEmbedding, fetchLimit);
    }

    /** 展开方向（v3.02）：展开开关关闭时强制 NONE（方向键被忽略，避免语义歧义） */
    private GraphRecords.ExpandDirection expandDirection() {
        if (!properties.isExpandNeighbors()) {
            return GraphRecords.ExpandDirection.NONE;
        }
        GraphRecords.ExpandDirection direction = properties.getExpandDirection();
        return direction == null ? GraphRecords.ExpandDirection.BOTH : direction;
    }

    /** 候选上限（v3.02）：不得低于种子上限（种子恒在，网关侧再兜底一次） */
    private int candidateLimit() {
        return Math.max(properties.getEntityTopN(), properties.getCandidateLimit());
    }

    /**
     * 索引过取条数 = 种子上限 × 过取倍数（v2.85）。
     *
     * <p>Neo4j 5.26 无索引内过滤，租户条件只能后过滤——他租户近邻会占满 topN 名额
     * 使本租户零种子。过取即名额补偿：索引侧多取，租户过滤后由网关封顶回
     * {@code entityTopN}（配 {@code rag.graph.retrieval.entity-over-fetch}）。
     */
    private int fetchLimit(int seedLimit) {
        return Math.max(seedLimit, seedLimit * Math.max(1, properties.getEntityOverFetch()));
    }

    /**
     * 空召回归因（v3.03 复核修正 F3/F10）：<b>仅最终 documents 为空时调用</b>
     * （多一次 Neo4j 往返，命中路径零成本），用<b>放大窗口</b>区分三态——
     * 饿死（本租户确有阈值内实体却挤不进检索窗口，调过取倍数有依据）｜
     * 锚点/GAP 缺口（窗口内有本租户种子却零召回）｜
     * 正常空（本租户相关实体在阈值之外或图内无数据）。
     *
     * <p>计数落 {@code rag.retrieval.graph.seed_starved / anchor_gap}；诊断自身故障
     * 只降级为「无归因读数」——绝不改变图路空结果语义（单路容错纪律）。
     */
    private void diagnoseEmptyRetrieval(String tenantId, float[] queryEmbedding, int fetchLimit) {
        try {
            int wideLimit = Math.max(fetchLimit * DIAGNOSTIC_WINDOW_MULTIPLIER, DIAGNOSTIC_WINDOW_MIN);
            GraphRecords.GraphRetrievalDiagnostics diagnostics = graphGateway.diagnoseRetrieval(
                tenantId, queryEmbedding, fetchLimit, wideLimit,
                properties.getEntitySimilarityThreshold());
            if (diagnostics.starved()) {
                metrics.recordGraphSeedStarved();
                log.debug("图路空召回归因=种子被跨租户后过滤饿死（放大窗口 {} 内有本租户阈值内实体 {}，"
                    + "检索窗口 0；可调大 rag.graph.retrieval.entity-over-fetch）: tenantId={}",
                    wideLimit, diagnostics.tenantSeeds(), tenantId);
            } else if (diagnostics.anchorGap()) {
                metrics.recordGraphAnchorGap();
                log.debug("图路空召回归因=锚点链路缺口（检索窗口内本租户种子={}，零存活锚点）: tenantId={}",
                    diagnostics.tenantSeedsInWindow(), tenantId);
            } else if (diagnostics.coldTenant()) {
                log.debug("图路空召回归因=本租户在放大窗口内无阈值内实体（正常空，不计饿死）: tenantId={}",
                    tenantId);
            }
        } catch (Exception e) {
            log.debug("图路归因诊断失败（不影响主路径空结果语义）: {}", e.getMessage());
        }
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
        meta.put(Constants.Retrieval.META_CHUNK_ID, chunk.getId());
        meta.put(Constants.Retrieval.META_DOC_ID, doc.getId());
        meta.put(Constants.Retrieval.META_TENANT_ID, doc.getTenantId());
        meta.put(Constants.Retrieval.META_CHUNK_TYPE, chunk.getChunkType() != null ? chunk.getChunkType().name() : ChunkType.TEXT.name());
        if (doc.getName() != null) {
            meta.put(Constants.Retrieval.META_FILE_NAME, doc.getName());
        }
        if (chunk.getPageNum() != null) {
            meta.put(Constants.Retrieval.META_PAGE_NUM, chunk.getPageNum());
        }
        meta.put(Constants.Retrieval.META_GRAPH_SCORE, hit.score());
        meta.put(Constants.Retrieval.ROUTE_GRAPH + Constants.Retrieval.RANK_KEY_SUFFIX, rank);
        meta.put("graph_hop", hit.hop());
        meta.put(Constants.Retrieval.META_GRAPH_ENTITY_HITS, String.join("，", hit.entityNames()));
        meta.put(Constants.Retrieval.META_RETRIEVAL_SOURCE, Constants.Retrieval.ROUTE_GRAPH);
        return Document.builder()
            .id(chunk.getId())
            .text(chunk.getContent())
            .metadata(meta)
            .score(hit.score())
            .build();
    }
}
