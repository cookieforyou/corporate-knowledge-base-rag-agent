package com.enterprise.kb.infrastructure.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.Result;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j 图谱网关实现（Phase 5 簇④）。
 *
 * <p>全部查询参数化（$参数绑定，无字符串拼接）；租户过滤强制在场——
 * 空租户写路径直接拒绝、读路径返回空（与检索侧两层 fail-closed 纪律同口径）。
 *
 * <p>图 Schema（幂等初始化，{@link #ensureSchema()}）：
 * <ul>
 *   <li>{@code Entity}：实体节点，{@code id} 唯一约束（{@link GraphIds} 派生），
 *       {@code embedding} 1024 维余弦向量索引（与主检索链路 EmbeddingModel 同源——
 *       pgvector/Milvus/语义缓存三处 1024 钉死同值）；</li>
 *   <li>{@code Chunk}：chunk 锚点（不存内容，PG 为事实源），{@code id} 唯一约束；</li>
 *   <li>{@code MENTIONS}：Chunk→Entity 提及关系；</li>
 *   <li>{@code RELATED_TO}：Entity→Entity 语义关系（幂等键 = 源×目标×类型），
 *       {@code doc_ids}/{@code chunk_ids} 溯源引用列表。</li>
 * </ul>
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

    public Neo4jGraphGateway(Driver driver, Neo4jProperties properties) {
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(properties.getDatabase());
        this.txConfig = TransactionConfig.builder()
            .withTimeout(Duration.ofSeconds(properties.getQueryTimeoutSeconds()))
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
            """
            CREATE VECTOR INDEX %s IF NOT EXISTS
            FOR (e:Entity) ON (e.embedding)
            OPTIONS {indexConfig: {
              `vector.dimensions`: %d,
              `vector.similarity_function`: 'cosine'
            }}
            """.formatted(ENTITY_VECTOR_INDEX, EMBEDDING_DIMENSIONS));
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                for (String statement : ddl) {
                    tx.run(statement);
                }
                return null;
            }, txConfig);
        }
        log.info("图谱 Schema 幂等初始化完成（约束 ×2 / 索引 ×2 / 向量索引 ×1，维度 {}）", EMBEDDING_DIMENSIONS);
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
        List<GraphRecords.RelationWrite> safeRelations = relations == null ? List.of() : relations;
        List<Map<String, Object>> mentionPairs = buildMentionPairs(entities);
        try (Session session = driver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                // 阶段一：清除该文档既有图引用（幂等重写前置，重入库收敛无残留）
                tx.run(REMOVE_DOC_REFERENCES, Map.of("tenantId", tenantId, "docId", docId));
                tx.run(GC_ORPHANS, Map.of("tenantId", tenantId));
                // 阶段二：写入新抽取结果（MERGE 语义）
                if (!chunks.isEmpty()) {
                    tx.run(MERGE_CHUNK_ANCHORS, Map.of(
                        "tenantId", tenantId, "docId", docId, "chunks", toChunkParams(chunks)));
                }
                if (!entities.isEmpty()) {
                    tx.run(MERGE_ENTITIES, Map.of(
                        "tenantId", tenantId, "docId", docId, "entities", toEntityParams(entities)));
                }
                if (!mentionPairs.isEmpty()) {
                    tx.run(MERGE_MENTIONS, Map.of("tenantId", tenantId, "mentions", mentionPairs));
                }
                if (!safeRelations.isEmpty()) {
                    tx.run(MERGE_RELATIONS, Map.of(
                        "tenantId", tenantId, "docId", docId, "relations", toRelationParams(safeRelations)));
                }
                return null;
            }, txConfig);
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
                tx.run(REMOVE_DOC_REFERENCES, Map.of("tenantId", tenantId, "docId", docId));
                tx.run(GC_ORPHANS, Map.of("tenantId", tenantId));
                return null;
            }, txConfig);
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
            }, txConfig);
        }
    }

    // ── 读路径（图路检索单管线） ──────────────────────────────────────

    @Override
    public List<GraphRecords.GraphChunkHit> retrieveChunks(String tenantId,
                                                           float[] queryEmbedding,
                                                           int entityTopN,
                                                           double similarityThreshold,
                                                           boolean expandNeighbors,
                                                           int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();   // fail-closed：无租户零触达
        }
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return List.of();
        }
        String cypher = expandNeighbors ? RETRIEVE_WITH_EXPANSION : RETRIEVE_SEEDS_ONLY;
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Result result = tx.run(cypher, Map.of(
                    "indexName", ENTITY_VECTOR_INDEX,
                    "topN", entityTopN,
                    "vector", Values.value(queryEmbedding),
                    "tenantId", tenantId,
                    "threshold", similarityThreshold,
                    "limit", limit));
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
    public List<GraphRecords.EntityChainSample> sampleEntityChains(String tenantId, int limit) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();   // fail-closed：无租户零触达
        }
        try (Session session = driver.session(sessionConfig)) {
            return session.executeRead(tx -> {
                Result result = tx.run(SAMPLE_ENTITY_CHAINS, Map.of(
                    "tenantId", tenantId, "limit", Math.max(1, limit)));
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
            list.add(Map.of("id", chunk.id(), "chunkIndex", chunk.chunkIndex()));
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

    /** 清除文档图引用：摘除实体/关系引用列表 + 删除 Chunk 锚点（孤儿清扫另行执行） */
    private static final String REMOVE_DOC_REFERENCES = """
        MATCH (c:Chunk {tenant_id: $tenantId, doc_id: $docId})
        WITH collect(c.id) AS oldChunkIds
        OPTIONAL MATCH (e:Entity {tenant_id: $tenantId})
        WHERE $docId IN e.doc_ids
        SET e.doc_ids = [x IN e.doc_ids WHERE x <> $docId],
            e.chunk_ids = [x IN coalesce(e.chunk_ids, []) WHERE NOT x IN oldChunkIds]
        WITH oldChunkIds
        OPTIONAL MATCH (:Entity)-[r:RELATED_TO]->(:Entity)
        WHERE r.tenant_id = $tenantId AND $docId IN r.doc_ids
        SET r.doc_ids = [x IN r.doc_ids WHERE x <> $docId],
            r.chunk_ids = [x IN coalesce(r.chunk_ids, []) WHERE NOT x IN oldChunkIds]
        WITH oldChunkIds
        MATCH (c:Chunk {tenant_id: $tenantId, doc_id: $docId})
        DETACH DELETE c
        """;

    /** 孤儿清扫：引用归零的实体与关系删除（DETACH 连带其余边） */
    private static final String GC_ORPHANS = """
        MATCH (e:Entity {tenant_id: $tenantId})
        WHERE size(coalesce(e.doc_ids, [])) = 0
        DETACH DELETE e
        WITH 1 AS dummy
        MATCH ()-[r:RELATED_TO]->()
        WHERE r.tenant_id = $tenantId AND size(coalesce(r.doc_ids, [])) = 0
        DELETE r
        """;

    private static final String MERGE_CHUNK_ANCHORS = """
        UNWIND $chunks AS ch
        MERGE (c:Chunk {id: ch.id})
        SET c.tenant_id = $tenantId, c.doc_id = $docId,
            c.chunk_index = ch.chunkIndex, c.is_deleted = false
        """;

    /**
     * 实体合并写入：幂等键 = id（租户×名称×类型派生）。
     * 合并语义：描述/嵌入取最新，doc_ids/chunk_ids 取并集，mention_count 累加。
     */
    private static final String MERGE_ENTITIES = """
        UNWIND $entities AS ent
        MERGE (e:Entity {id: ent.id})
        ON CREATE SET e.tenant_id = $tenantId, e.name = ent.name, e.type = ent.type,
                      e.description = ent.description, e.embedding = ent.embedding,
                      e.doc_ids = [$docId], e.chunk_ids = ent.chunkIds,
                      e.mention_count = 1, e.created_at = datetime(), e.updated_at = datetime()
        ON MATCH SET e.description = ent.description, e.embedding = ent.embedding,
                     e.doc_ids = CASE WHEN $docId IN e.doc_ids THEN e.doc_ids ELSE e.doc_ids + $docId END,
                     e.chunk_ids = coalesce(e.chunk_ids, []) + [x IN ent.chunkIds WHERE NOT x IN coalesce(e.chunk_ids, [])],
                     e.mention_count = coalesce(e.mention_count, 0) + 1,
                     e.updated_at = datetime()
        """;

    private static final String MERGE_MENTIONS = """
        UNWIND $mentions AS m
        MATCH (c:Chunk {id: m.chunkId, tenant_id: $tenantId}),
              (e:Entity {id: m.entityId, tenant_id: $tenantId})
        MERGE (c)-[:MENTIONS]->(e)
        """;

    /** 关系合并写入：幂等键 = (源, 目标, relation_type)；溯源引用列表并集更新 */
    private static final String MERGE_RELATIONS = """
        UNWIND $relations AS rel
        MATCH (s:Entity {id: rel.sourceId, tenant_id: $tenantId}),
              (t:Entity {id: rel.targetId, tenant_id: $tenantId})
        MERGE (s)-[r:RELATED_TO {relation_type: rel.relationType}]->(t)
        ON CREATE SET r.tenant_id = $tenantId, r.description = rel.description,
                      r.doc_ids = [$docId], r.chunk_ids = rel.chunkIds, r.weight = 1.0
        ON MATCH SET r.doc_ids = CASE WHEN $docId IN r.doc_ids THEN r.doc_ids ELSE r.doc_ids + $docId END,
                     r.chunk_ids = coalesce(r.chunk_ids, []) + [x IN rel.chunkIds WHERE NOT x IN coalesce(r.chunk_ids, [])],
                     r.weight = coalesce(r.weight, 1.0) + 1.0,
                     r.description = rel.description
        """;

    private static final String SET_CHUNKS_DELETED = """
        UNWIND $chunkIds AS cid
        MATCH (c:Chunk {id: cid, tenant_id: $tenantId})
        SET c.is_deleted = $deleted
        """;

    /** 图路检索（仅种子实体，不展开）：向量匹配 → MENTIONS 反查存活锚点 */
    private static final String RETRIEVE_SEEDS_ONLY = """
        CALL db.index.vector.queryNodes($indexName, $topN, $vector) YIELD node AS e, score
        WHERE e.tenant_id = $tenantId AND score >= $threshold
        WITH e, score
        MATCH (c:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(e)
        WITH c, max(score) AS chunkScore, collect(DISTINCT e.name)[0..5] AS entityNames, 0 AS hop
        RETURN c.id AS chunkId, c.doc_id AS docId, chunkScore, entityNames, hop
        ORDER BY chunkScore DESC, hop ASC
        LIMIT $limit
        """;

    /** 图路检索（种子 + 1 跳邻域展开，邻居贡献衰减 0.5） */
    private static final String RETRIEVE_WITH_EXPANSION = """
        CALL db.index.vector.queryNodes($indexName, $topN, $vector) YIELD node AS e, score
        WHERE e.tenant_id = $tenantId AND score >= $threshold
        WITH e, score
        OPTIONAL MATCH (e)-[:RELATED_TO]-(n:Entity {tenant_id: $tenantId})
        WITH e, score, collect(DISTINCT n) AS neighbors
        UNWIND ([{ent: e, s: score, hop: 0}]
                + [x IN neighbors | {ent: x, s: score * %f, hop: 1}]) AS cand
        WITH cand.ent AS ent, cand.s AS contrib, cand.hop AS hop
        MATCH (c:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(ent)
        WITH c, max(contrib) AS chunkScore, collect(DISTINCT ent.name)[0..5] AS entityNames,
             min(hop) AS hop
        RETURN c.id AS chunkId, c.doc_id AS docId, chunkScore, entityNames, hop
        ORDER BY chunkScore DESC, hop ASC
        LIMIT $limit
        """.formatted(NEIGHBOR_DECAY);

    private static final String COUNT_BY_TENANT = """
        MATCH (e:Entity {tenant_id: $tenantId})
        WITH count(e) AS entities
        OPTIONAL MATCH ()-[r:RELATED_TO {tenant_id: $tenantId}]->()
        WITH entities, count(r) AS relations
        OPTIONAL MATCH (c:Chunk {tenant_id: $tenantId})
        RETURN entities, relations, count(c) AS chunkAnchors
        """;

    /** 二跳实体链采样（多跳草稿材料）：a→b→c 链 + 链首/尾关联存活 chunk 反查 */
    private static final String SAMPLE_ENTITY_CHAINS = """
        MATCH (a:Entity {tenant_id: $tenantId})-[:RELATED_TO]->(b:Entity {tenant_id: $tenantId})
              -[:RELATED_TO]->(c:Entity {tenant_id: $tenantId})
        WHERE a.id <> c.id
        WITH DISTINCT a, b, c
        LIMIT $limit
        OPTIONAL MATCH (ca:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(a)
        OPTIONAL MATCH (cc:Chunk {tenant_id: $tenantId, is_deleted: false})-[:MENTIONS]->(c)
        WITH a, b, c, collect(DISTINCT ca.id) AS caIds, collect(DISTINCT cc.id) AS ccIds
        RETURN [a.name, b.name, c.name] AS chain, caIds + ccIds AS chunkIds
        """;
}
