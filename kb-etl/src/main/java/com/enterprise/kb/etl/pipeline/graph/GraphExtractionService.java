package com.enterprise.kb.etl.pipeline.graph;

import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.GraphStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.infrastructure.graph.GraphIds;
import com.enterprise.kb.infrastructure.graph.GraphRecords;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图谱抽取编排（簇④ 5.1）：文档 → 逐 chunk 结构化抽取 → 实体嵌入 → 幂等写图
 * → 状态回写。
 *
 * <p><b>定位</b>：ETL 成功后的异步旁路——抽取成败不影响文档入库主状态
 * （{@code DocumentStatus}），状态机独立落 {@code kb_document.graph_status}。
 * 触发双入口（批2 接线）：① ETL COMPLETED 终态帧派发（覆盖首次入库 / reparse /
 * replace / 重建）；② kb-admin 存量回填任务直调。
 *
 * <p><b>限流双层</b>（避业务高峰 + 供应商护栏）：
 * <ul>
 *   <li>Redisson 令牌桶 {@code rag:ratelimit:graph-extraction:{tenantId}}
 *       （缺省 20 次/分钟/租户，{@code RateLimitAdvisor} 同形态）——获取超时
 *       跳过该 chunk（成本管控非安全边界，不击穿文档级抽取）；</li>
 *   <li>JVM 信号量（缺省 3 在飞）——虚拟线程无界，显式闸门防打爆供应商
 *       （{@code ContextualEnrichmentTransformer} 同先例）。</li>
 * </ul>
 *
 * <p><b>实体嵌入批量化</b>（v2.78 实证提速）：描述嵌入按
 * {@code embedBatchSize}（缺省 10，同 ETL 分批纪律）单请求批量调用，
 * 批失败批内回落逐条（保单点失败隔离），维度快失败守卫不变。
 *
 * <p><b>幂等</b>：写图经 {@link GraphGateway#replaceDocumentGraph}（单事务清除
 * 该文档既有图引用再写入），重复触发（重入库/回填重发）天然收敛。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphExtractionService {

    static final String RATE_LIMITER_KEY_PREFIX = "rag:ratelimit:graph-extraction:";

    /** 令牌获取等待上限：等待即排队，超时跳过该 chunk（后台管道不无限阻塞） */
    private static final long RATE_ACQUIRE_TIMEOUT_SECONDS = 30;

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final EntityExtractor entityExtractor;
    private final EmbeddingModel embeddingModel;
    private final GraphGateway graphGateway;
    private final RedissonClient redissonClient;
    private final GraphExtractionProperties properties;
    private final List<GraphExtractionListener> listeners;
    private final ExecutorService extractionExecutor;
    private final Semaphore concurrencyGate;

    public GraphExtractionService(KbDocumentRepository documentRepository,
                                  KbChunkRepository chunkRepository,
                                  EntityExtractor entityExtractor,
                                  EmbeddingModel embeddingModel,
                                  GraphGateway graphGateway,
                                  RedissonClient redissonClient,
                                  GraphExtractionProperties properties,
                                  ObjectProvider<GraphExtractionListener> listenerProvider) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.entityExtractor = entityExtractor;
        this.embeddingModel = embeddingModel;
        this.graphGateway = graphGateway;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.listeners = listenerProvider.orderedStream().toList();
        this.extractionExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.concurrencyGate = new Semaphore(Math.max(1, properties.getConcurrency()));
    }

    @PreDestroy
    void shutdown() {
        extractionExecutor.close();
    }

    /**
     * 抽取单文档并幂等写图。
     *
     * @return true = 抽取成功写图；false = 跳过/失败（守卫拒绝、文档缺失、管道异常）
     */
    public boolean extract(String tenantId, String docId) {
        KbDocument doc = documentRepository.findById(docId).orElse(null);
        if (doc == null || !doc.getTenantId().equals(tenantId)) {
            log.warn("图谱抽取拒绝：文档不存在或租户不匹配（fail-closed）: docId={}", docId);
            return false;
        }
        markStatus(doc, GraphStatus.EXTRACTING);

        List<KbChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndex(docId).stream()
            .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
            .toList();
        List<KbChunk> candidates = chunks.stream().filter(this::extractable).toList();
        if (candidates.isEmpty()) {
            markStatus(doc, GraphStatus.COMPLETED);
            return true;    // 无可抽取内容 = 完成态（无实体文档合法存在）
        }
        listeners.forEach(l -> l.extractionStarted(tenantId, docId, candidates.size()));

        try {
            // 阶段一：逐 chunk 结构化抽取（有界并发 + 令牌桶限流，失败隔离）
            ExtractionResult[] results = extractChunks(tenantId, chunks, candidates);

            // 阶段二：实体合并（确定性 ID 收敛）+ 描述嵌入（维度快失败守卫）
            List<EntityAggregate> aggregates = mergeEntities(tenantId, chunks, candidates, results);
            AtomicInteger embeddingSkipped = new AtomicInteger();
            List<GraphRecords.EntityWrite> entityWrites = embedEntities(aggregates, embeddingSkipped);

            // 阶段三：关系解析（名称→ID，越集丢弃）+ 幂等写图
            List<GraphRecords.RelationWrite> relationWrites =
                resolveRelations(tenantId, chunks, candidates, results, aggregates);
            List<GraphRecords.ChunkAnchor> anchors = aggregates.stream()
                .flatMap(a -> a.chunkIds().stream())
                .distinct()
                .map(id -> new GraphRecords.ChunkAnchor(id, chunkIndexOf(chunks, id)))
                .toList();
            graphGateway.replaceDocumentGraph(tenantId, docId, anchors, entityWrites, relationWrites);

            markStatus(doc, GraphStatus.COMPLETED);
            listeners.forEach(l -> l.extractionSucceeded(tenantId, docId,
                entityWrites.size(), relationWrites.size()));
            log.info("图谱抽取完成: docId={}, chunks={}/{}, 实体={}, 关系={}, 嵌入跳过={}",
                docId, candidates.size(), chunks.size(), entityWrites.size(),
                relationWrites.size(), embeddingSkipped.get());
            return true;
        } catch (Exception e) {
            markStatus(doc, GraphStatus.FAILED);
            listeners.forEach(l -> l.extractionFailed(tenantId, docId, e.getMessage()));
            log.warn("图谱抽取失败（不阻断文档主状态，可经回填任务重试）: docId={}, {}",
                docId, e.getMessage());
            return false;
        }
    }

    // ── 阶段一：逐 chunk 抽取 ─────────────────────────────────────────

    private ExtractionResult[] extractChunks(String tenantId, List<KbChunk> allChunks,
                                             List<KbChunk> candidates) {
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < allChunks.size(); i++) {
            indexById.put(allChunks.get(i).getId(), i);
        }
        ExtractionResult[] slots = new ExtractionResult[allChunks.size()];
        AtomicInteger rateLimited = new AtomicInteger();
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();

        for (KbChunk candidate : candidates) {
            int slot = indexById.get(candidate.getId());
            inFlight.add(CompletableFuture.runAsync(() -> {
                try {
                    concurrencyGate.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    if (!tryAcquireToken(tenantId)) {
                        rateLimited.incrementAndGet();
                        return;     // 限流排队超时：跳过该 chunk（成本管控非正确性边界）
                    }
                    KbChunk previous = slot > 0 ? allChunks.get(slot - 1) : null;
                    KbChunk next = slot + 1 < allChunks.size() ? allChunks.get(slot + 1) : null;
                    slots[slot] = entityExtractor.extract(
                        truncate(previous), truncate(candidate.getContent()), truncate(next));
                } finally {
                    concurrencyGate.release();
                }
            }, extractionExecutor));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        if (rateLimited.get() > 0) {
            log.warn("图谱抽取限流跳过 {} 个 chunk（租户桶 {} 次/{}s）",
                rateLimited.get(), properties.getRate(), properties.getRateIntervalSeconds());
        }
        return slots;
    }

    private boolean tryAcquireToken(String tenantId) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(RATE_LIMITER_KEY_PREFIX + tenantId);
            limiter.setRate(RateLimiterArgs.of(RateType.OVERALL,
                properties.getRate(), Duration.ofSeconds(properties.getRateIntervalSeconds())));
            return limiter.tryAcquire(1, RATE_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis 故障 fail-open（抽取是旁路管道，限流是成本管控非安全边界）
            log.warn("图谱抽取限流器故障，fail-open 放行: {}", e.getMessage());
            return true;
        }
    }

    // ── 阶段二：实体合并与嵌入 ────────────────────────────────────────

    /** 文档内实体聚合态（确定性 ID 收敛同名同类型，跨 chunk 引用合并） */
    record EntityAggregate(String id, String name, String type, String description,
                           List<String> chunkIds) {
    }

    private List<EntityAggregate> mergeEntities(String tenantId, List<KbChunk> allChunks,
                                                List<KbChunk> candidates, ExtractionResult[] results) {
        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < allChunks.size(); i++) {
            indexById.put(allChunks.get(i).getId(), i);
        }
        Map<String, EntityAggregate> table = new LinkedHashMap<>();
        for (KbChunk candidate : candidates) {
            ExtractionResult result = results[indexById.get(candidate.getId())];
            if (result == null || result.entities().isEmpty()) {
                continue;
            }
            for (ExtractionResult.EntityExtraction entity : result.entities()) {
                if (entity.name() == null || entity.name().isBlank()) {
                    continue;
                }
                String id = GraphIds.entityId(tenantId, entity.name(), entity.type());
                table.compute(id, (k, existing) -> {
                    if (existing == null) {
                        return new EntityAggregate(id, GraphIds.normalizeName(entity.name()),
                            entity.type() == null ? "OTHER" : entity.type().trim().toUpperCase(),
                            entity.description(), new ArrayList<>(List.of(candidate.getId())));
                    }
                    existing.chunkIds().add(candidate.getId());
                    // 描述取最长（信息量更大的胜出，跨文档重抽同语义）
                    String description = longer(existing.description(), entity.description());
                    return new EntityAggregate(existing.id(), existing.name(), existing.type(),
                        description, existing.chunkIds());
                });
            }
        }
        return table.values().stream()
            .sorted(Comparator.comparing(EntityAggregate::name))
            .toList();
    }

    private List<GraphRecords.EntityWrite> embedEntities(List<EntityAggregate> aggregates,
                                                         AtomicInteger skipped) {
        List<GraphRecords.EntityWrite> writes = new ArrayList<>(aggregates.size());
        int batchSize = Math.max(1, properties.getEmbedBatchSize());
        for (int from = 0; from < aggregates.size(); from += batchSize) {
            List<EntityAggregate> batch =
                aggregates.subList(from, Math.min(from + batchSize, aggregates.size()));
            Map<String, float[]> vectors = embedBatch(batch);
            for (EntityAggregate aggregate : batch) {
                if (!vectors.containsKey(aggregate.id())) {
                    skipped.incrementAndGet();   // 批内回落逐条时单点失败——跳过该实体不击穿文档
                    continue;
                }
                float[] embedding = vectors.get(aggregate.id());
                if (embedding == null || embedding.length != GraphGateway.ENTITY_EMBEDDING_DIMENSIONS) {
                    throw new IllegalStateException("实体嵌入维度不符（期望 "
                        + GraphGateway.ENTITY_EMBEDDING_DIMENSIONS + "，实际 "
                        + (embedding == null ? "null" : embedding.length) + "）——嵌入源与图索引不同源");
                }
                writes.add(new GraphRecords.EntityWrite(aggregate.id(), aggregate.name(),
                    aggregate.type(), aggregate.description() == null ? "" : aggregate.description(),
                    embedding, aggregate.chunkIds()));
            }
        }
        return writes;
    }

    /** 实体嵌入语料：描述优先，空描述落名称（与批量化前逐条口径同语义） */
    private static String embeddingCorpus(EntityAggregate aggregate) {
        return aggregate.description() == null || aggregate.description().isBlank()
            ? aggregate.name() : aggregate.description();
    }

    /**
     * 批量嵌入（10 条/批，同 ETL {@code kb.etl.embed-batch-size} 纪律——
     * DashScope embedding 单请求硬限 ≤20 条）：批量请求 + index 防御性归位；
     * 批失败/响应形态异常 → 批内回落逐条调用，保单点失败隔离语义不退化。
     *
     * @return entityId → 向量；失败跳过的实体键缺位（调用方计数跳过）
     */
    private Map<String, float[]> embedBatch(List<EntityAggregate> batch) {
        List<String> corpus = batch.stream().map(GraphExtractionService::embeddingCorpus).toList();
        try {
            float[][] ordered = placeByIndex(
                embeddingModel.embedForResponse(corpus).getResults(), batch.size());
            if (ordered != null) {
                Map<String, float[]> vectors = new LinkedHashMap<>();
                for (int i = 0; i < batch.size(); i++) {
                    vectors.put(batch.get(i).id(), ordered[i]);
                }
                return vectors;
            }
            log.warn("实体嵌入批量响应形态异常（条数/下标不符），回落逐条嵌入: batchSize={}", batch.size());
        } catch (Exception e) {
            log.warn("实体嵌入批量调用失败，回落逐条嵌入: batchSize={}, {}", batch.size(), e.getMessage());
        }
        Map<String, float[]> vectors = new LinkedHashMap<>();
        for (EntityAggregate aggregate : batch) {
            try {
                vectors.put(aggregate.id(), embeddingModel.embed(embeddingCorpus(aggregate)));
            } catch (Exception e) {
                log.warn("实体嵌入失败（该实体不写图）: name={}, {}", aggregate.name(), e.getMessage());
            }
        }
        return vectors;
    }

    /** 批量响应按 index 归位输入序；条数/下标异常（越界、重复、空洞）返回 null 触发回落 */
    private static float[][] placeByIndex(List<Embedding> results, int expected) {
        if (results.size() != expected) {
            return null;
        }
        float[][] vectors = new float[expected][];
        for (int i = 0; i < results.size(); i++) {
            Integer index = results.get(i).getIndex();
            int slot = index == null ? i : index;
            if (slot < 0 || slot >= expected || vectors[slot] != null) {
                return null;
            }
            vectors[slot] = results.get(i).getOutput();
        }
        for (float[] vector : vectors) {
            if (vector == null) {
                return null;
            }
        }
        return vectors;
    }

    // ── 阶段三：关系解析 ──────────────────────────────────────────────

    private List<GraphRecords.RelationWrite> resolveRelations(String tenantId,
                                                              List<KbChunk> allChunks,
                                                              List<KbChunk> candidates,
                                                              ExtractionResult[] results,
                                                              List<EntityAggregate> aggregates) {
        // 名称 → ID（规范化名称键，与 GraphIds.normalizeName 同口径）
        Map<String, String> idByName = new HashMap<>();
        for (EntityAggregate aggregate : aggregates) {
            idByName.put(aggregate.name(), aggregate.id());
        }
        // results 数组按全量 chunk 下标索引（与阶段一槽位同口径）
        Map<String, Integer> slotById = new HashMap<>();
        for (int i = 0; i < allChunks.size(); i++) {
            slotById.put(allChunks.get(i).getId(), i);
        }
        Map<String, GraphRecords.RelationWrite> dedup = new LinkedHashMap<>();
        AtomicInteger unresolved = new AtomicInteger();
        for (KbChunk candidate : candidates) {
            ExtractionResult result = results[slotById.get(candidate.getId())];
            if (result == null || result.relations().isEmpty()) {
                continue;
            }
            for (ExtractionResult.RelationExtraction relation : result.relations()) {
                String sourceId = idByName.get(GraphIds.normalizeName(relation.sourceName()));
                String targetId = idByName.get(GraphIds.normalizeName(relation.targetName()));
                if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
                    unresolved.incrementAndGet();   // 越集/自环关系丢弃（宁缺毋滥）
                    continue;
                }
                String type = relation.relationType() == null || relation.relationType().isBlank()
                    ? "RELATED_TO" : relation.relationType().trim().toUpperCase();
                String key = sourceId + "|" + targetId + "|" + type;
                dedup.compute(key, (k, existing) -> existing == null
                    ? new GraphRecords.RelationWrite(sourceId, targetId, type,
                        relation.description(), new ArrayList<>(List.of(candidate.getId())))
                    : withChunk(existing, candidate.getId()));
            }
        }
        if (unresolved.get() > 0) {
            log.debug("图谱关系解析丢弃越集/自环关系 {} 条", unresolved.get());
        }
        return new ArrayList<>(dedup.values());
    }

    private static GraphRecords.RelationWrite withChunk(GraphRecords.RelationWrite existing, String chunkId) {
        List<String> chunkIds = new ArrayList<>(existing.chunkIds());
        if (!chunkIds.contains(chunkId)) {
            chunkIds.add(chunkId);
        }
        return new GraphRecords.RelationWrite(existing.sourceId(), existing.targetId(),
            existing.relationType(), existing.description(), chunkIds);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────

    private boolean extractable(KbChunk chunk) {
        if (chunk.getChunkType() == ChunkType.IMAGE) {
            return false;   // 图片 chunk 正文为标签无语义（语境增强同纪律）
        }
        return chunk.getContent() != null
            && chunk.getContent().strip().length() >= properties.getMinChunkChars();
    }

    private String truncate(KbChunk chunk) {
        if (chunk == null || chunk.getContent() == null) {
            return null;
        }
        return truncate(chunk.getContent());
    }

    private String truncate(String text) {
        int max = properties.getMaxChunkChars();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String longer(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.length() >= b.length() ? a : b;
    }

    private static int chunkIndexOf(List<KbChunk> chunks, String chunkId) {
        for (KbChunk chunk : chunks) {
            if (chunk.getId().equals(chunkId)) {
                return chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex();
            }
        }
        return 0;
    }

    private void markStatus(KbDocument doc, GraphStatus status) {
        doc.setGraphStatus(status);
        doc.setGraphUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }
}
