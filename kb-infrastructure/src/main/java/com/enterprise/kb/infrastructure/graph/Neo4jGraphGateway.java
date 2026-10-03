package com.enterprise.kb.infrastructure.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.Result;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 图谱网关实现（Phase5簇④）。
 *
 * <p>全部查询参数化（$参数绑定，无字符串拼接）；租户过滤强制在场——
 * 空租户写路径直接拒绝、读路径返回空（与检索侧两层 fail-closed 纪律同口径）。
 *
 * <p>图 Schema（幂等初始化，{@link #ensureSchema()}）：
 * <ul>
 *   <li>{@code Entity}：实体节点，{@code id} 唯一约束（{@link GraphIds} 派生），
 *       {@code embedding} 1024 维余弦向量索引（与主检索链路 EmbeddingModel 同源——
 *       pgvector/Milvus/语义缓存三处 1024 钉死同值），{@code tenant_id} 范围索引；</li>
 *   <li>{@code Chunk}：chunk 锚点（不存内容，PG 为事实源），{@code id} 唯一约束 +
 *       {@code doc_id}/{@code tenant_id} 范围索引；</li>
 *   <li>{@code MENTIONS}：Chunk→Entity 提及关系；</li>
 *   <li>{@code RELATED_TO}：Entity→Entity 语义关系（幂等键 = 源×目标×类型），
 *       {@code doc_ids}/{@code chunk_ids} 溯源引用列表 + <b>{@code tenant_id} 关系属性索引</b>
 *       （v3.00 补：租户域清扫/计数从全库关系类型扫描收敛为索引 seek）。</li>
 * </ul>
 *
 * <p><b>维度三处守卫（v3.00）</b>：向量维度是三处配置的共同契约（图常量 × 向量库
 * {@code kb.vector-store.*} × 缓存 {@code rag.cache.embedding-dim}），任一处漂移都会
 * 静默降级——真库实证：向 1024 维索引写 768 维向量<b>不报错、不落日志、事务成功，
 * 但该节点对向量索引永久不可见</b>；且 {@code CREATE VECTOR INDEX IF NOT EXISTS}
 * 对维度漂移既不报错也不改维度，故启动期读回自省是唯一信号面。守卫三层：
 * ① 写入侧 {@link #requireEmbedding}（快失败，兑现接口契约）；② 读路径维度前置守卫
 * （返回空 + ERROR，不让 Neo4j 兜底抛错）；③ {@link #verifyVectorIndexDimensions}
 * 既有索引维度读回比对。
 */
@Slf4j
public class Neo4jGraphGateway implements GraphGateway {

    /** 实体向量索引名（检索路径经名引用） */
    static final String ENTITY_VECTOR_INDEX = "entity_embedding";

    /** 向量维度——与主检索链路 EmbeddingModel 同源（1024 三处钉死），改此值须同步图索引重建 */
    static final int EMBEDDING_DIMENSIONS = GraphGateway.ENTITY_EMBEDDING_DIMENSIONS;

    /** 邻域展开衰减系数（1 跳邻居贡献 = 种子分 × 0.5） */
    private static final double NEIGHBOR_DECAY = 0.5;

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final TransactionConfig txConfig;
    /** 写路径事务上限（v3.02 分设：读 5s / 写 30s，见构造器注释） */
    private final TransactionConfig writeTxConfig;

    public Neo4jGraphGateway(Driver driver, Neo4jProperties properties) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(properties.getDatabase());
        this.txConfig = TransactionConfig.builder()
            .withTimeout(Duration.ofSeconds(properties.getQueryTimeoutSeconds()))
            .build();
        // 读写超时分设（v3.02）：读路径 5s 宽裕，写路径（幂等重写 + 两段孤儿清扫 +
        // 批量 MERGE）随文档规模线性增长——实测 300 实体 + 299 关系单事务 ≈2s，
        // 千级实体大文档贴近 5s 上限即被服务端掐断（整篇抽取白跑）
        this.writeTxConfig = TransactionConfig.builder()
            .withTimeout(Duration.ofSeconds(properties.getWriteTimeoutSeconds()))
            .build();
    }

    // ── Schema 与连通性 ──────────────────────────────────────────────

    @Override
    public void ensureSchema() {
        List<String> ddl = List.of(
            "CREATE CONSTRAINT kb_entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE",
            "CREATE CONSTRAINT kb_chunk_id IF NOT EXISTS FOR (c:Chunk) REQUIRE c.id IS UNIQUE",
            "CREATE INDEX kb_entity_tenant IF NOT EXISTS FOR (e:Entity) ON (e.tenant_id)",
            "CREATE INDEX kb_chunk_doc IF NOT EXISTS FOR (c:Chunk) ON (c.doc_id)",
            // v3.00 补两项索引：前者使租户域关系清扫/计数从全库关系类型扫描
            // （DirectedRelationshipTypeScan + Eager）收敛为索引 seek；后者使
            // countByTenant 的锚点统计不再全库扫 Chunk 标签（实测计划取证）
            "CREATE INDEX kb_chunk_tenant IF NOT EXISTS FOR (c:Chunk) ON (c.tenant_id)",
            "CREATE INDEX kb_relation_tenant IF NOT EXISTS FOR ()-[r:RELATED_TO]-() ON (r.tenant_id)",
            """
            CREATE VECTOR INDEX %s IF NOT EXISTS
            FOR (e:Entity) ON (e.embedding)
            OPTIONS {indexConfig: {
              `vector.dimensions`: %d,
              `vector.similarity_function`: 'cosine'
            }}
            """.formatted(ENTITY_VECTOR_INDEX, EMBEDDING_DIMENSIONS));   // 纯整型占位，locale 无关
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                for (String statement : ddl) {
                    tx.run(statement);
                }
                return null;
            }, txConfig);
            verifyVectorIndexDimensions(session);
        }
        log.info("图谱 Schema 幂等初始化完成（约束 ×2 / 索引 ×4 / 向量索引 ×1，维度 {}）", EMBEDDING_DIMENSIONS);
    }

    /**
     * 既有向量索引维度读回自省（v3.00）：{@code CREATE VECTOR INDEX … IF NOT EXISTS}
     * 对维度漂移既不报错也不改维度（真库实证：拿 768 重放后索引仍 1024），故必须读回比对。
     * 不符只 ERROR 显形不阻断启动——图是检索增强件，由运行期读路径守卫兜底空路
     * （{@link #ensureSchema()} 失败不阻断启动的同一条纪律）。
     */
    private void verifyVectorIndexDimensions(Session session) {
        try {
            Result result = session.run(
                "SHOW INDEXES YIELD name, options WHERE name = $indexName "
                    + "RETURN options.indexConfig.`vector.dimensions` AS dimensions",
                Map.of("indexName", ENTITY_VECTOR_INDEX));
            if (!result.hasNext()) {
                return;   // 索引缺位（本次 DDL 首建）——无需比对
            }
            Value dimensions = result.next().get("dimensions");
            if (dimensions.isNull()) {
                return;
            }
            long actual = dimensions.asLong();
            if (actual != EMBEDDING_DIMENSIONS) {
                log.error("图向量索引维度与代码常量不符：索引 {} 维 / 期望 {} 维——IF NOT EXISTS "
                    + "不会重建索引，实体写入将对向量检索永久不可见（实测语义，无报错无日志）；"
                    + "处置 = DROP INDEX {} 后重启（重建）→ 回填存量实体",
                    actual, EMBEDDING_DIMENSIONS, ENTITY_VECTOR_INDEX);
            }
        } catch (Exception e) {
            log.warn("图向量索引维度自省失败（不阻断启动）: {}", e.getMessage());
        }
    }

    @Override
    public void verifyConnectivity() {
        driver.verifyConnectivity();
    }

    // ── 写路径 ────────────────────────────────────────────────────────

    @Override
    public void replaceDocumentGraph(String tenantId,
                                     String docId,
                                     List<GraphRecords.ChunkAnchor> chunks,
                                     List<GraphRecords.EntityWrite> entities,
                                     List<GraphRecords.RelationWrite> relations) {
        requireTenant(tenantId);
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("图谱写入缺失 docId");
        }
        List<GraphRecords.ChunkAnchor> safeChunks = chunks == null ? List.of() : chunks;
        List<GraphRecords.EntityWrite> safeEntities = entities == null ? List.of() : entities;
        List<GraphRecords.RelationWrite> safeRelations = relations == null ? List.of() : relations;
        requireEmbeddings(safeEntities);   // 写前快失败：维度不符不进事务（兑现接口契约）
        List<Map<String, Object>> mentionPairs = buildMentionPairs(safeEntities);
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                // 阶段一：清除该文档既有图引用（幂等重写前置，重入库收敛无残留）
                // keepChunkIds = 本次锚点集（PG 现存全量 chunk）——锚点与 MENTIONS 边对其保留
                tx.run(REMOVE_DOC_REFERENCES, Map.of(
                    "tenantId", tenantId, "docId", docId, "keepChunkIds", chunkIdsOf(safeChunks)));
                // 阶段二：写入新抽取结果（MERGE 语义）
                if (!safeChunks.isEmpty()) {
                    tx.run(MERGE_CHUNK_ANCHORS, Map.of(
                        "tenantId", tenantId, "docId", docId, "chunks", toChunkParams(safeChunks)));
                }
                if (!safeEntities.isEmpty()) {
                    tx.run(MERGE_ENTITIES, Map.of(
                        "tenantId", tenantId, "docId", docId, "entities", toEntityParams(safeEntities)));
                }
                if (!mentionPairs.isEmpty()) {
                    tx.run(MERGE_MENTIONS, Map.of("tenantId", tenantId, "mentions", mentionPairs));
                }
                if (!safeRelations.isEmpty()) {
                    tx.run(MERGE_RELATIONS, Map.of(
                        "tenantId", tenantId, "docId", docId, "relations", toRelationParams(safeRelations)));
                }
                // 阶段三：孤儿清扫（v3.03 复核修正 F1）——**必须排在重写之后**：阶段一摘除
                // doc_ids 后、重写补回之前，被重写的实体会瞬时「零文档引用」成孤儿，此时清扫
                // 会 DETACH DELETE 掉它及其全部 MENTIONS 边（含保留锚点的边）→ 软删恢复后图路
                // 仍不可召回。移到重写后：已重写实体 doc_ids 已补回（非孤儿），真正失引的实体/
                // 关系才被清除，收敛语义不变
                gcOrphans(tx, tenantId);
                // 阶段四：计数重算（v3.03 F2）——落库值恒等于引用列表大小，与算式演进解耦
                if (!safeEntities.isEmpty()) {
                    tx.run(REFRESH_MENTION_COUNTS, Map.of(
                        "tenantId", tenantId, "entityIds", entityIdsOf(safeEntities)));
                }
                if (!safeRelations.isEmpty()) {
                    tx.run(REFRESH_RELATION_WEIGHTS, Map.of(
                        "tenantId", tenantId, "relations", toRelationParams(safeRelations)));
                }
                return null;
            }, writeTxConfig);
        }
    }

    @Override
    public void removeDocument(String tenantId, String docId) {
        requireTenant(tenantId);
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("图谱删除缺失 docId");
        }
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                // 文档删除语义不变：keepChunkIds 空 → 该文档全部锚点（连同 MENTIONS 边）删除
                tx.run(REMOVE_DOC_REFERENCES, Map.of(
                    "tenantId", tenantId, "docId", docId, "keepChunkIds", List.of()));
                gcOrphans(tx, tenantId);
                return null;
            }, writeTxConfig);
        }
    }

    @Override
    public void setChunksDeleted(String tenantId, Collection<String> chunkIds, boolean deleted) {
        requireTenant(tenantId);
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run(SET_CHUNKS_DELETED, Map.of(
                    "tenantId", tenantId, "chunkIds", new ArrayList<>(chunkIds), "deleted", deleted));
                return null;
            }, writeTxConfig);
        }
    }

    // ── 读路径（图路检索单管线） ──────────────────────────────────────

    @Override
    public List<GraphRecords.GraphChunkHit> retrieveChunks(String tenantId,
                                                           GraphRecords.GraphRetrievalSpec spec) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();   // fail-closed：无租户零触达
        }
        if (spec == null || !isQueryVectorUsable(spec.queryEmbedding())) {
            return List.of();
        }
        String cypher = retrievalCypher(spec.expandDirection());
        int seedLimit = Math.max(1, spec.entityTopN());
        int fetchLimit = fetchCap(seedLimit, spec.entityFetchLimit());
        int candidateLimit = candidateCap(seedLimit, spec.candidateLimit());
        int neighborLimit = neighborCap(candidateLimit, spec.neighborLimit());
        if (fetchLimit != spec.entityFetchLimit() || candidateLimit != spec.candidateLimit()
            || neighborLimit != spec.neighborLimit()) {
            // 兜底改写不静默（v3.03 F8）：配置值非法（过取/候选/邻域低于下界）时留痕
            log.debug("图路检索参数被兜底改写（fetch/candidate 不得低于种子上限，neighbor 非正值回落候选上限）："
                    + "fetch {}→{}, candidate {}→{}, neighbor {}→{}, 种子上限={}",
                spec.entityFetchLimit(), fetchLimit, spec.candidateLimit(), candidateLimit,
                spec.neighborLimit(), neighborLimit, seedLimit);
        }
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, Map.of(
                    "indexName", ENTITY_VECTOR_INDEX,
                    "fetchLimit", fetchLimit,
                    "seedLimit", seedLimit,
                    "candidateLimit", candidateLimit,
                    "neighborLimit", neighborLimit,
                    "vector", Values.value(spec.queryEmbedding()),
                    "tenantId", tenantId,
                    "threshold", spec.similarityThreshold(),
                    "limit", Math.max(1, spec.limit())));
                List<GraphRecords.GraphChunkHit> hits = new ArrayList<>();
                while (result.hasNext()) {
                    Record record = result.next();
                    hits.add(new GraphRecords.GraphChunkHit(
                        record.get("chunkId").asString(),
                        record.get("docId").asString(),
                        record.get("chunkScore").asDouble(),
                        record.get("entityNames").asList(Value::asString),
                        record.get("hop").asInt()));
                }
                return hits;
            }, txConfig);
        }
    }

    /** 展开方向 → 检索 Cypher（NONE = 仅种子；BOTH/OUTGOING/INCOMING = 关系模式方向） */
    private static String retrievalCypher(GraphRecords.ExpandDirection direction) {
        if (direction == null || direction == GraphRecords.ExpandDirection.NONE) {
            return RETRIEVE_SEEDS_ONLY;
        }
        String pattern = switch (direction) {
            case OUTGOING -> "-[:RELATED_TO]->";
            case INCOMING -> "<-[:RELATED_TO]-";
            default -> "-[:RELATED_TO]-";   // BOTH
        };
        // Locale.ROOT（v3.03 复核修正 F4）：String.formatted 走默认 locale，JDK 21 下
        // de_DE 等区域会把 %f 渲染成 "0,500000" → Cypher 语法错 → 展开路整段降级空路
        return String.format(Locale.ROOT, RETRIEVE_WITH_EXPANSION_TEMPLATE, pattern, NEIGHBOR_DECAY);
    }

    @Override
    public GraphRecords.GraphRetrievalDiagnostics diagnoseRetrieval(String tenantId,
                                                                    float[] queryEmbedding,
                                                                    int entityFetchLimit,
                                                                    int wideFetchLimit,
                                                                    double similarityThreshold) {
        GraphRecords.GraphRetrievalDiagnostics zero = GraphRecords.GraphRetrievalDiagnostics.EMPTY;
        if (tenantId == null || tenantId.isBlank() || !isQueryVectorUsable(queryEmbedding)) {
            return zero;   // fail-closed：无租户/无向量/维度不符零触达
        }
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                int window = Math.max(1, entityFetchLimit);
                Record record = tx.run(DIAGNOSE_RETRIEVAL, Map.of(
                    "indexName", ENTITY_VECTOR_INDEX,
                    "fetchLimit", window,
                    "wideLimit", Math.max(window, wideFetchLimit),   // 放大窗口须 ≥ 检索窗口
                    "vector", Values.value(queryEmbedding),
                    "tenantId", tenantId,
                    "threshold", similarityThreshold)).single();
                return new GraphRecords.GraphRetrievalDiagnostics(
                    (int) record.get("wideCandidates").asLong(),
                    (int) record.get("tenantSeeds").asLong(),
                    (int) record.get("tenantSeedsInWindow").asLong());
            }, txConfig);
        }
    }

    @Override
    public GraphCounts countByTenant(String tenantId) {
        requireTenant(tenantId);
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Record record = tx.run(COUNT_BY_TENANT, Map.of("tenantId", tenantId)).single();
                return new GraphCounts(
                    record.get("entities").asLong(),
                    record.get("relations").asLong(),
                    record.get("chunkAnchors").asLong());
            }, txConfig);
        }
    }

    @Override
    public List<GraphRecords.EntityChainSample> sampleEntityChains(String tenantId, int limit,
                                                                   int seedLimit) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();   // fail-closed：无租户零触达
        }
        int rows = Math.max(1, limit);
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Result result = tx.run(SAMPLE_ENTITY_CHAINS, Map.of(
                    "tenantId", tenantId,
                    "limit", rows,
                    "seedLimit", Math.max(rows, seedLimit)));   // 链首采样不得少于目标链数
                List<GraphRecords.EntityChainSample> samples = new ArrayList<>();
                while (result.hasNext()) {
                    Record record = result.next();
                    samples.add(new GraphRecords.EntityChainSample(
                        record.get("chain").asList(Value::asString),
                        record.get("chunkIds").asList(Value::asString)));
                }
                return samples;
            }, txConfig);
        }
    }

    // ── 参数转换与守卫 ────────────────────────────────────────────────

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("图谱写入拒绝：缺失租户身份（fail-closed）");
        }
    }

    /**
     * 实体嵌入守卫（v3.00，兑现 {@link GraphGateway} 的写入侧契约）：null 或维度不符一律拒绝。
     *
     * <p>必要性 = 真库实证的静默失败语义：向 1024 维向量索引写入 768 维向量<b>不报错、
     * 不落日志、事务成功</b>，但该节点对向量索引永久不可见（检索永不召回，且无任何痕迹）；
     * {@code Values.value(null)} 亦只落 {@code NullValue}（属性被清除、同样不进索引）。
     * 落地纪律 = 网关自守（不依赖调用方守卫，kb-etl 侧守卫保留为双保险）。
     * 包内可见供单测直断（快失败边界 ≤ 维度契约）。
     */
    static void requireEmbeddings(List<GraphRecords.EntityWrite> entities) {
        for (GraphRecords.EntityWrite entity : entities) {
            float[] embedding = entity.embedding();
            if (embedding == null || embedding.length != EMBEDDING_DIMENSIONS) {
                throw new IllegalArgumentException("实体嵌入维度不符（期望 " + EMBEDDING_DIMENSIONS
                    + "，实际 " + (embedding == null ? "null" : embedding.length)
                    + "）——嵌入源与图向量索引不同源，拒绝写入以免索引静默失配（改维须重建索引）: "
                    + "entityId=" + entity.id());
            }
        }
    }

    /** 过取条数兜底（≤ 种子上限即退化为不放过取——旧行为；包内可见供单测直断） */
    static int fetchCap(int seedLimit, int entityFetchLimit) {
        return Math.max(seedLimit, entityFetchLimit);
    }

    /** 候选上限兜底（不得低于种子上限：种子恒在是候选封顶的语义前提；包内可见供单测直断） */
    static int candidateCap(int seedLimit, int candidateLimit) {
        return Math.max(seedLimit, candidateLimit);
    }

    /**
     * 单种子邻居采样上限兜底（v3.04）：<b>非正值（未配置 / 非法）回落候选上限</b>——
     * 即 v3.02 的等价上界（单种子最多贡献到候选预算），不引入新的召回收紧；正值一律
     * 按显式配置生效（低于候选上限 = 主动收紧单种子采样：内存更省、多种子更易分享
     * 候选预算；高于 = 更接近不截断）。包内可见供单测直断。
     */
    static int neighborCap(int candidateLimit, int neighborLimit) {
        return neighborLimit > 0 ? neighborLimit : Math.max(1, candidateLimit);
    }

    /**
     * 查询向量可用性守卫（v3.00）：空向量与<b>维度不符</b>一律判不可用。
     *
     * <p>维度不符若不前置拦截，会由 Neo4j 侧抛 {@code IllegalArgumentException}
     * （"Index query vector has 768 dimensions, but indexed vectors have 1024"）——
     * 虽被单路容错吞成空路，但错误归因落在「图库」而非「嵌入源与索引不同源」；
     * 此处显式 ERROR 显形（维度漂移的启动期信号面之一）。
     */
    private static boolean isQueryVectorUsable(float[] queryEmbedding) {
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return false;
        }
        if (queryEmbedding.length != EMBEDDING_DIMENSIONS) {
            log.error("图路查询向量维度不符（期望 {}，实际 {}）——嵌入源与图向量索引不同源，"
                + "本路返回空（处置：维度对齐后重建图向量索引并回填）",
                EMBEDDING_DIMENSIONS, queryEmbedding.length);
            return false;
        }
        return true;
    }

    /**
     * 租户域孤儿清扫（实体段 + 关系段，两条独立语句）。
     *
     * <p><b>不可合并为单条 Cypher</b>：空结果短路会让「无孤儿实体」时的关系段永不执行
     * （详见 {@link #GC_ORPHAN_RELATIONS}）。两条语句同事务执行——原子性不变，
     * 短路与行放大（原形态关系段被驱动 N 次）一并消除。
     */
    private static void gcOrphans(TransactionContext tx, String tenantId) {
        tx.run(GC_ORPHAN_ENTITIES, Map.of("tenantId", tenantId));
        tx.run(GC_ORPHAN_RELATIONS, Map.of("tenantId", tenantId));
    }

    /** 锚点 id 集（幂等重写的 keep 列表：这些锚点与其 MENTIONS 边保留，其余删除） */
    private static List<String> chunkIdsOf(List<GraphRecords.ChunkAnchor> chunks) {
        return chunks.stream().map(GraphRecords.ChunkAnchor::id).toList();
    }

    /** 实体 id 集（计数重算语句的 UNWIND 材料） */
    private static List<String> entityIdsOf(List<GraphRecords.EntityWrite> entities) {
        return entities.stream().map(GraphRecords.EntityWrite::id).toList();
    }

    /** 实体 → chunk 提及对展开（MENTIONS 写入材料） */
    private static List<Map<String, Object>> buildMentionPairs(List<GraphRecords.EntityWrite> entities) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (GraphRecords.EntityWrite entity : entities) {
            for (String chunkId : entity.chunkIds()) {
                pairs.add(Map.of("chunkId", chunkId, "entityId", entity.id()));
            }
        }
        return pairs;
    }

    private static List<Map<String, Object>> toChunkParams(List<GraphRecords.ChunkAnchor> chunks) {
        List<Map<String, Object>> list = new ArrayList<>(chunks.size());
        for (GraphRecords.ChunkAnchor chunk : chunks) {
            list.add(Map.of("id", chunk.id(), "chunkIndex", chunk.chunkIndex(),
                "isDeleted", chunk.isDeleted()));
        }
        return list;
    }

    private static List<Map<String, Object>> toEntityParams(List<GraphRecords.EntityWrite> entities) {
        List<Map<String, Object>> list = new ArrayList<>(entities.size());
        for (GraphRecords.EntityWrite entity : entities) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", entity.id());
            map.put("name", entity.name());
            map.put("type", entity.type());
            map.put("description", entity.description() == null ? "" : entity.description());
            map.put("embedding", Values.value(entity.embedding()));
            map.put("chunkIds", entity.chunkIds());
            list.add(map);
        }
        return list;
    }

    private static List<Map<String, Object>> toRelationParams(List<GraphRecords.RelationWrite> relations) {
        List<Map<String, Object>> list = new ArrayList<>(relations.size());
        for (GraphRecords.RelationWrite relation : relations) {
            Map<String, Object> map = new HashMap<>();
            map.put("sourceId", relation.sourceId());
            map.put("targetId", relation.targetId());
            map.put("relationType", relation.relationType());
            map.put("description", relation.description() == null ? "" : relation.description());
            map.put("chunkIds", relation.chunkIds());
            list.add(map);
        }
        return list;
    }

    // ── Cypher 常量 ───────────────────────────────────────────────────

    /**
     * 清除文档图引用：摘除实体/关系引用列表 + 删除<b>已离开 PG</b> 的 Chunk 锚点
     * （孤儿清扫另行执行）。
     *
     * <p><b>保留在场锚点（v3.03 复核修正 F1）</b>：{@code $keepChunkIds} = 本次写入的锚点集
     * （= PG 该文档现存全量 chunk）。原形态无条件 {@code DETACH DELETE} 该文档全部锚点——
     * 锚点一删其 {@code MENTIONS} 边随之消失，而 {@code MERGE_MENTIONS} 只为<b>可抽取</b>
     * chunk 重建边，故「软删期间发生过重抽取」的 chunk 在 restore 后<b>锚点在、边不在</b>，
     * 图路仍不可召回（复核实证：restore 后只返回 K2，K1 未复活）。改为保留在场锚点后：
     * 锚点节点与其 MENTIONS 边全程存活（{@code is_deleted} 由 {@link #MERGE_CHUNK_ANCHORS}
     * 按 PG 标记刷新），restore 仅翻标记即恢复图路可召回；仅当 PG 已无该 chunk（物理删除）
     * 时才连带删除锚点。
     *
     * <p><b>引用列表只摘「已离开」的 chunk id</b>：软删 chunk 仍被实体真实提及（MENTIONS 边
     * 在场），故其 id 保留在 {@code chunk_ids} 中——保证 {@code mention_count = size(chunk_ids)}
     * 与边集一致（原形态会摘掉软删 chunk 的 id，使元数据低于真实提及数）。
     */
    private static final String REMOVE_DOC_REFERENCES = """
        MATCH (c:Chunk {tenant_id: $tenantId, doc_id: $docId})
        WITH collect(c.id) AS oldChunkIds
        WITH [x IN oldChunkIds WHERE NOT x IN $keepChunkIds] AS departedChunkIds
        OPTIONAL MATCH (e:Entity {tenant_id: $tenantId})
        WHERE $docId IN e.doc_ids
        SET e.doc_ids = [x IN e.doc_ids WHERE x <> $docId],
            e.chunk_ids = [x IN coalesce(e.chunk_ids, []) WHERE NOT x IN departedChunkIds]
        WITH departedChunkIds
        OPTIONAL MATCH (:Entity)-[r:RELATED_TO]->(:Entity)
        WHERE r.tenant_id = $tenantId AND $docId IN r.doc_ids
        SET r.doc_ids = [x IN r.doc_ids WHERE x <> $docId],
            r.chunk_ids = [x IN coalesce(r.chunk_ids, []) WHERE NOT x IN departedChunkIds]
        WITH departedChunkIds
        MATCH (c:Chunk {tenant_id: $tenantId, doc_id: $docId})
        WHERE c.id IN departedChunkIds
        DETACH DELETE c
        """;

    /** 孤儿清扫：引用归零的实体与关系删除（DETACH 连带其余边） */
    private static final String GC_ORPHAN_ENTITIES = """
        MATCH (e:Entity {tenant_id: $tenantId})
        WHERE size(coalesce(e.doc_ids, [])) = 0
        DETACH DELETE e
        """;

    /**
     * 孤儿清扫（关系段）：引用归零的关系删除。
     *
     * <p><b>必须与实体段拆成两条独立语句</b>（v2.85 实证）：Cypher 空结果会短路后续子句，
     * 两段串联时「本租户无孤儿实体」使关系段永不执行——空引用关系永久残留（实测：
     * 重抽取未产出关系时，关系 doc_ids/chunk_ids 归零但节点留存，继续参与邻域展开与计数）；
     * 且串联形态首段逐行传递，使关系段被驱动 N 次（N = 孤儿实体数）并叠加 Eager 缓冲。
     * 拆开后短路与行放大一并消除。
     *
     * <p>匹配形态从租户实体锚定（而非 {@code ()-[r:RELATED_TO]->()} + 租户过滤）：走
     * {@code kb_entity_tenant} 索引 + Expand，避开全库关系类型扫描（实测原形态为
     * {@code DirectedRelationshipTypeScan} + Eager，随全租户关系总量线性退化）；
     * 关系两端必为同租户实体（{@link #MERGE_RELATIONS} 写入侧以 tenant_id 约束两端），
     * 故以源端实体锚定与全量扫描的租户过滤语义等价。
     */
    private static final String GC_ORPHAN_RELATIONS = """
        MATCH (:Entity {tenant_id: $tenantId})-[r:RELATED_TO]->()
        WHERE size(coalesce(r.doc_ids, [])) = 0
        DELETE r
        """;

    /**
     * Chunk 锚点写入（v3.02）：锚点集合 = <b>PG 全量 chunk</b>（含软删与不可抽取片段），
     * {@code is_deleted} 随 PG 标记走。原形态只写「含实体 chunk」且恒置 false——与
     * 幂等重写阶段一「删除该文档全部锚点」叠加，软删期间的重抽取会让该 chunk 永久
     * 失去锚点（restore 翻转不到任何节点，图路召回静默丢失至下次重抽取）。
     */
    private static final String MERGE_CHUNK_ANCHORS = """
        UNWIND $chunks AS ch
        MERGE (c:Chunk {id: ch.id})
        SET c.tenant_id = $tenantId, c.doc_id = $docId,
            c.chunk_index = ch.chunkIndex, c.is_deleted = ch.isDeleted
        """;

    /**
     * 实体合并写入：幂等键 = id（租户×名称×类型派生）。
     * 合并语义：<b>描述与嵌入「信息量更大者胜」</b>（v3.01），doc_ids/chunk_ids 取并集
     * （<b>真去重</b>：v3.03 复核修正 F2——用 {@code reduce} 去重，原并集式只与赋值前的
     * {@code chunk_ids} 比对，入参内部重复（同 chunk 抽取结果同实体出现两次）会漏去重）。
     * <b>计数不在此处累加</b>：{@code mention_count} 由 {@link #REFRESH_MENTION_COUNTS}
     * 按 {@code size(chunk_ids)} 重算（v3.02 语义 = 去重片段数；v3.03 改为独立重算语句，
     * 使「mention_count = size(chunk_ids)」成为结构性不变量而非算式巧合）。
     *
     * <p><b>描述策略（v3.01 修正）</b>：原「取最新」在跨文档时会把已存的长描述冲成短描述，
     * 甚至被空描述冲成 {@code ""}——而嵌入语料在描述为空时回落<b>名称向量</b>
     * （{@code GraphExtractionService#embeddingCorpus}），即一条无描述文档会把该实体
     * 已存的好描述与好向量一并降级（embedding 是检索键，直接伤向量召回）。实证：
     * 长描述 + 描述向量写后，空描述文档再写 → {@code description=""，embedding=名称向量}。
     * 现策与 ETL 文档内策略（{@code mergeEntities} 取最长）统一为「较长者胜」。
     * <b>配对纪律</b>：{@code SET} 子句内所有项读的都是赋值前的值，故 description 与
     * embedding 的两个 CASE 判据一致、不会错位（IT 实证：保留「长描述 + 描述向量」）。
     */
    private static final String MERGE_ENTITIES = """
        UNWIND $entities AS ent
        MERGE (e:Entity {id: ent.id})
        ON CREATE SET e.tenant_id = $tenantId, e.name = ent.name, e.type = ent.type,
                      e.description = ent.description, e.embedding = ent.embedding,
                      e.doc_ids = [$docId],
                      e.chunk_ids = reduce(acc = [], x IN ent.chunkIds |
                          CASE WHEN x IN acc THEN acc ELSE acc + [x] END),
                      e.mention_count = size(ent.chunkIds),
                      e.created_at = datetime(), e.updated_at = datetime()
        ON MATCH SET e.description = CASE WHEN size(coalesce(e.description, '')) >= size(ent.description)
                                          THEN e.description ELSE ent.description END,
                     e.embedding = CASE WHEN size(coalesce(e.description, '')) >= size(ent.description)
                                        THEN e.embedding ELSE ent.embedding END,
                     e.doc_ids = CASE WHEN $docId IN e.doc_ids THEN e.doc_ids ELSE e.doc_ids + $docId END,
                     e.chunk_ids = reduce(acc = [], x IN coalesce(e.chunk_ids, []) + ent.chunkIds |
                         CASE WHEN x IN acc THEN acc ELSE acc + [x] END),
                     e.updated_at = datetime()
        """;

    /**
     * 实体提及计数重算（v3.03 复核修正 F2）：{@code mention_count = size(chunk_ids)}。
     *
     * <p>独立语句而非 {@code MERGE_ENTITIES} 内的算式——使不变量<b>结构性成立</b>：
     * 无论入参是否含重复、无论并集算式如何演进，落库值恒等于去重片段数
     * （用户侧 E2E 判据 {@code mention_count == size(e.chunk_ids)} 因此必然成立，
     * 不会掩盖语义偏差）。写入实体数为百级，重算为一次属性读取 + 写入，成本可忽略。
     */
    private static final String REFRESH_MENTION_COUNTS = """
        UNWIND $entityIds AS id
        MATCH (e:Entity {id: id, tenant_id: $tenantId})
        SET e.mention_count = size(coalesce(e.chunk_ids, []))
        """;

    private static final String MERGE_MENTIONS = """
        UNWIND $mentions AS m
        MATCH (c:Chunk {id: m.chunkId, tenant_id: $tenantId}),
              (e:Entity {id: m.entityId, tenant_id: $tenantId})
        MERGE (c)-[:MENTIONS]->(e)
        """;

    /**
     * 关系合并写入：幂等键 = (源, 目标, relation_type)；溯源引用列表并集更新。
     *
     * <p><b>weight 语义重算（v3.02）</b>：{@code weight = 关联文档数}（doc_ids 并集大小）——
     * 原「每次 ON MATCH +1.0」是写入次数，同一文档重抽即重复计数，非真实关系强度。
     *
     * <p><b>描述策略（v3.01 与实体同策）</b>：原 {@code ON MATCH} 直接覆盖为最新描述，
     * 多文档描述不同即反复互覆、丢失历史；现改「信息量更大者胜」（较长者保留），
     * 与实体描述策略及 ETL 文档内「首见保留」取向一致（后者保首见，前者保信息量，
     * 统一判据 = 不被更短/空的描述降级）。
     */
    private static final String MERGE_RELATIONS = """
        UNWIND $relations AS rel
        MATCH (s:Entity {id: rel.sourceId, tenant_id: $tenantId}),
              (t:Entity {id: rel.targetId, tenant_id: $tenantId})
        MERGE (s)-[r:RELATED_TO {relation_type: rel.relationType}]->(t)
        ON CREATE SET r.tenant_id = $tenantId, r.description = rel.description,
                      r.doc_ids = [$docId],
                      r.chunk_ids = reduce(acc = [], x IN rel.chunkIds |
                          CASE WHEN x IN acc THEN acc ELSE acc + [x] END),
                      r.weight = 1.0
        ON MATCH SET r.doc_ids = CASE WHEN $docId IN r.doc_ids THEN r.doc_ids ELSE r.doc_ids + $docId END,
                     r.chunk_ids = reduce(acc = [], x IN coalesce(r.chunk_ids, []) + rel.chunkIds |
                         CASE WHEN x IN acc THEN acc ELSE acc + [x] END),
                     r.description = CASE WHEN size(coalesce(r.description, '')) >= size(rel.description)
                                          THEN r.description ELSE rel.description END
        """;

    /** 关系权重重算（v3.03 复核修正 F2 同策）：{@code weight = toFloat(size(doc_ids))} = 关联文档数 */
    private static final String REFRESH_RELATION_WEIGHTS = """
        UNWIND $relations AS rel
        MATCH (s:Entity {id: rel.sourceId, tenant_id: $tenantId})
              -[r:RELATED_TO {relation_type: rel.relationType}]->
              (t:Entity {id: rel.targetId, tenant_id: $tenantId})
        SET r.weight = toFloat(size(coalesce(r.doc_ids, [])))
        """;

    private static final String SET_CHUNKS_DELETED = """
        UNWIND $chunkIds AS cid
        MATCH (c:Chunk {id: cid, tenant_id: $tenantId})
        SET c.is_deleted = $deleted
        """;

    /** 图路检索（仅种子实体，不展开）：向量过取 → 租户过滤 + 阈值 → 种子封顶 → MENTIONS 反查存活锚点 */
    private static final String RETRIEVE_SEEDS_ONLY = """
        CALL db.index.vector.queryNodes($indexName, $fetchLimit, $vector) YIELD node AS e, score
        WHERE e.tenant_id = $tenantId AND score >= $threshold
        WITH e, score ORDER BY score DESC LIMIT $seedLimit
        MATCH (c:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(e)
        WITH c, max(score) AS chunkScore, collect(DISTINCT e.name)[0..5] AS entityNames, 0 AS hop
        RETURN c.id AS chunkId, c.doc_id AS docId, chunkScore, entityNames, hop
        ORDER BY chunkScore DESC, hop ASC
        LIMIT $limit
        """;

    /**
     * 图路检索（种子 + 1 跳邻域展开，邻居贡献衰减 0.5）。
     *
     * <p>三处版本结构：① 关系模式由 {@link #retrievalCypher} 按展开方向注入
     * （{@code %s}——仅三种编译期字面量，无外部输入拼接）；② 候选实体在 MENTIONS
     * 反查前<b>排序 + 封顶</b>（{@code candidateLimit}）——种子 hop=0 恒排前故恒在，
     * 只截断低贡献邻居；排序含 {@code ent.id} 兜底键，保证同图同查询结果可复现。
     * 计划实证（v3.00）：MENTIONS 展开 + 聚合原本整体落在最终 {@code Top(LIMIT)} 之前，
     * 无早剪枝。③ 单种子<b>邻居采样上限</b>（{@code $neighborLimit}，v3.04）——
     * 变量作用域子查询内 `Top(LIMIT) → collect` 使邻域收集本身有界（计划实证：
     * {@code Apply → Top(LIMIT $neighborLimit) → EagerAggregation}），按
     * {@code size(chunk_ids) DESC, id ASC} 保留中心度最高的邻居；子查询内聚合无分组键
     * 故零邻居种子恒返回一行（空列表）不丢种子。④ 全局候选截断排序补
     * {@code size(chunk_ids) DESC} 次键（v3.04）：hop/贡献相同的邻居此前按 <b>id 字典序</b>
     * 定去留（对相关性无意义），现按中心度优先、{@code id} 仍作末位兜底键保可复现。
     *
     * <p><b>排序键为何读 {@code size(chunk_ids)} 而非物化字段 {@code mention_count}
     * （v3.05，真库取证）</b>：两者语义相同（{@code REFRESH_MENTION_COUNTS} 即把后者置为前者），
     * 但 {@code mention_count} 是<b>抽取期物化</b>的标量——v3.02 之前抽取的存量图里它仍是旧语义
     * （写入次数，恒为 1），此时按它排序会退化成 {@code id} 字典序，采样保留的就不是中心度最高的
     * 邻居。读列表长度则与写路径维护的 {@code chunk_ids} 同源，<b>不受计数新鲜度影响</b>；
     * 生产实证：某租户 748 实体图中 {@code mention_count} 全为 1 而 {@code chunk_ids} 最大 14。
     */
    private static final String RETRIEVE_WITH_EXPANSION_TEMPLATE = """
        CALL db.index.vector.queryNodes($indexName, $fetchLimit, $vector) YIELD node AS e, score
        WHERE e.tenant_id = $tenantId AND score >= $threshold
        WITH e, score ORDER BY score DESC LIMIT $seedLimit
        CALL (e) {
            MATCH (e)%s(n:Entity {tenant_id: $tenantId})
            WITH n ORDER BY size(coalesce(n.chunk_ids, [])) DESC, n.id ASC LIMIT $neighborLimit
            RETURN collect(n) AS neighbors
        }
        WITH e, score, neighbors
        UNWIND ([{ent: e, s: score, hop: 0}]
                + [x IN neighbors | {ent: x, s: score * %f, hop: 1}]) AS cand
        WITH cand.ent AS ent, cand.s AS contrib, cand.hop AS hop
        ORDER BY hop ASC, contrib DESC, size(coalesce(ent.chunk_ids, [])) DESC, ent.id ASC
        LIMIT $candidateLimit
        MATCH (c:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(ent)
        WITH c, max(contrib) AS chunkScore, collect(DISTINCT ent.name)[0..5] AS entityNames,
             min(hop) AS hop
        RETURN c.id AS chunkId, c.doc_id AS docId, chunkScore, entityNames, hop
        ORDER BY chunkScore DESC, hop ASC
        LIMIT $limit
        """;

    /**
     * 空召回归因读数（v3.03 复核修正 F3：双窗口判据）：检索窗口（{@code $fetchLimit}）与
     * <b>放大窗口</b>（{@code $wideLimit}）各取一次阈值内候选，返回「放大窗口候选总数 /
     * 放大窗口本租户实体数 / 检索窗口本租户实体数」。
     *
     * <p>判据意图：<b>饿死 = 本租户确有阈值内实体（放大窗口能看见）却挤不进检索窗口</b>。
     * 原形态用「无阈值 {@code EXISTS} 判本租户有无图数据」，无法区分「被挤占」与
     * 「本租户实体全在阈值之外」——后者放大窗口仍为 0，调过取永远无效（复核实证：
     * 仅一条 0.5 分实体、阈值 0.7 的租户被误判饿死，窗口放大到 1000 依旧）。
     * 聚合无分组键 → 恒返回一行，不会有空结果短路；两次索引调用均参数化。
     */
    private static final String DIAGNOSE_RETRIEVAL = """
        CALL db.index.vector.queryNodes($indexName, $fetchLimit, $vector) YIELD node AS e, score
        WHERE score >= $threshold
        WITH collect(e) AS windowCandidates
        CALL db.index.vector.queryNodes($indexName, $wideLimit, $vector) YIELD node AS w, score AS score2
        WHERE score2 >= $threshold
        WITH windowCandidates, collect(w) AS wideCandidates
        RETURN size(wideCandidates) AS wideCandidates,
               size([x IN wideCandidates WHERE x.tenant_id = $tenantId]) AS tenantSeeds,
               size([x IN windowCandidates WHERE x.tenant_id = $tenantId]) AS tenantSeedsInWindow
        """;

    private static final String COUNT_BY_TENANT = """
        MATCH (e:Entity {tenant_id: $tenantId})
        WITH count(e) AS entities
        OPTIONAL MATCH ()-[r:RELATED_TO {tenant_id: $tenantId}]->()
        WITH entities, count(r) AS relations
        OPTIONAL MATCH (c:Chunk {tenant_id: $tenantId})
        RETURN entities, relations, count(c) AS chunkAnchors
        """;

    /**
     * 二跳实体链采样（多跳草稿材料）：a→b→c 链 + 链首/尾关联存活 chunk 反查。
     *
     * <p><b>v3.02 有界 + 确定性</b>：先按 {@code a.id} 序有界采样链首（{@code seedLimit}）
     * 再展开——原形态直接从租户全域实体枚举起（计划实证 {@code NodeIndexSeek c:Entity →
     * Expand×2 → Distinct → Limit}，限额落在展开与去重之后），大租户/高连接度图下中间
     * 结果无界；且无 {@code ORDER BY} 时 {@code LIMIT} 结果随执行计划漂移，出题材料不可
     * 复现（eval 产物须可复现）。链三元组按 id 全序排序，同图同参输出恒定。
     */
    private static final String SAMPLE_ENTITY_CHAINS = """
        MATCH (a:Entity {tenant_id: $tenantId})
        WITH a ORDER BY a.id ASC LIMIT $seedLimit
        MATCH (a)-[:RELATED_TO]->(b:Entity {tenant_id: $tenantId})
              -[:RELATED_TO]->(c:Entity {tenant_id: $tenantId})
        WHERE a.id <> c.id
        WITH DISTINCT a, b, c
        ORDER BY a.id ASC, b.id ASC, c.id ASC
        LIMIT $limit
        OPTIONAL MATCH (ca:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(a)
        OPTIONAL MATCH (cc:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(c)
        WITH a, b, c, collect(DISTINCT ca.id) AS caIds, collect(DISTINCT cc.id) AS ccIds
        RETURN [a.name, b.name, c.name] AS chain, caIds + ccIds AS chunkIds
        """;
}
