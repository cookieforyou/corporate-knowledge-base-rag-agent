package com.enterprise.kb.infrastructure.graph;

import java.util.Collection;
import java.util.List;

/**
 * 知识图谱读写网关（Phase 5 簇④ GraphRAG）。
 *
 * <p>Neo4j 的唯一访问面：抽取写入（kb-etl）、图路检索（kb-ai-core）、生命周期清理
 * （kb-api 删除 / kb-admin 运维）均经本接口，Cypher 细节封闭在实现内。
 *
 * <p>租户隔离：所有方法强制携 {@code tenantId}，查询面参数化注入
 * {@code e.tenant_id = $tenantId}——fail-closed 语义与检索侧两层纪律同口径，
 * 实现侧对空租户拒绝执行（返回空/不写入）。
 *
 * <p>故障语义：网关不吞异常（调用方按「抽取失败不阻断 / 检索失败降级空路」
 * 各自容错），仅保证自身无状态、线程安全。
 */
public interface GraphGateway {

    /**
     * 实体向量维度——与主检索链路 EmbeddingModel 同源（1024 三处钉死：
     * pgvector / Milvus / 语义缓存同值）。写入侧嵌入维度不符即拒绝写图（快失败，
     * 防向量索引静默失配——同 pgvector idType 钉 TEXT 坑位的防御纵深思路）。
     */
    int ENTITY_EMBEDDING_DIMENSIONS = 1024;

    /** 幂等初始化图 Schema（约束 + 索引 + 1024 维向量索引），启动期执行 */
    void ensureSchema();

    /** 连通性校验（启动期；失败由调用方决定降级形态） */
    void verifyConnectivity();

    /**
     * 幂等替换文档子图（抽取主写路径）：单事务内先清除该文档既有图引用
     * （Chunk 锚点删除 + 实体/关系引用列表摘除 + 孤儿清扫），再写入新抽取结果。
     * 重入库（reparse/replace/重建）经本方法天然收敛，无残留引用。
     */
    void replaceDocumentGraph(String tenantId,
                              String docId,
                              List<GraphRecords.ChunkAnchor> chunks,
                              List<GraphRecords.EntityWrite> entities,
                              List<GraphRecords.RelationWrite> relations);

    /**
     * 删除文档全部图引用（文档删除路径，尽力而为语义由调用方把握）：
     * Chunk 锚点删除 + 实体/关系引用摘除 + 引用归零的实体/关系孤儿清扫。
     */
    void removeDocument(String tenantId, String docId);

    /** chunk 软删/恢复同步：翻转图内 Chunk 锚点 is_deleted 标记（实体引用保留） */
    void setChunksDeleted(String tenantId, Collection<String> chunkIds, boolean deleted);

    /**
     * 图路检索（单 Cypher 管线）：查询向量 → 向量索引实体匹配（租户过滤 + 阈值）
     * → 邻域展开（≤1 跳，衰减）→ MENTIONS 反查存活 Chunk 锚点，按贡献分降序返回。
     * 检索期零 LLM 调用；空租户返回空列表（fail-closed）。
     *
     * @param entityTopN        向量索引种子实体上限
     * @param similarityThreshold 种子相似度下限
     * @param expandNeighbors   是否 1 跳邻域展开（衰减系数 0.5 固定于实现）
     * @param limit             chunk 结果上限（对齐召回口径）
     */
    List<GraphRecords.GraphChunkHit> retrieveChunks(String tenantId,
                                                    float[] queryEmbedding,
                                                    int entityTopN,
                                                    double similarityThreshold,
                                                    boolean expandNeighbors,
                                                    int limit);

    /** 运维观测：租户域实体/关系/锚点计数（回填任务与 E2E 核验用） */
    GraphCounts countByTenant(String tenantId);

    /** 租户域图规模计数 */
    record GraphCounts(long entities, long relations, long chunkAnchors) {
    }
}
