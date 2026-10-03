package com.enterprise.kb.infrastructure.graph;

import java.util.Collection;
import java.util.List;

/**
 * 知识图谱读写网关（Phase5簇④ GraphRAG）。
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
     * <p><b>过取与种子封顶两段式</b>（v3.00，跨租户饿死修复）：Neo4j 5.26 的
     * {@code db.index.vector.queryNodes} 无索引内过滤——租户条件只能后过滤，他租户
     * 近邻会占满 topN 名额致本租户零种子（实证：他租户 6 条 1.0 分候选使本租户
     * 0.995 分候选完全不可见）。故索引侧按 {@code entityFetchLimit} 取候选，过滤后
     * 再按 {@code entityTopN} 封顶回原语义。
     *
     * <p><b>候选封顶与展开方向</b>（v3.02）：展开后候选实体按
     * {@code hop ASC, 贡献分 DESC, 实体 id ASC} 排序并截断至 {@code candidateLimit}
     * （种子恒在——hop=0 恒排前），使 <b>MENTIONS 反查前的候选实体集有界</b>
     * （注意作用域：候选之前的**邻域收集** `OPTIONAL MATCH + collect` 仍与种子度数成正比
     * ——超大度数场景需另按度采样，属后续项，勿把本上限当全链路有界保证）；
     * 展开方向由 {@link GraphRecords.ExpandDirection} 显式指定（模型有向，
     * 缺省双向保持既有召回行为）。
     *
     * @param spec 检索规格（种子上限 / 过取条数 / 阈值 / 展开方向 / 候选上限 / 结果上限）
     */
    List<GraphRecords.GraphChunkHit> retrieveChunks(String tenantId,
                                                    GraphRecords.GraphRetrievalSpec spec);

    /**
     * 图路空召回归因读数（v3.03 双窗口判据）：检索窗口 + <b>放大窗口</b>各取一次阈值内候选，
     * 返回「放大窗口候选数 / 放大窗口本租户实体数 / 检索窗口本租户实体数」——把
     * 「本租户确有相关实体却被挤占」（饿死，调过取倍数有依据）与「本租户实体全在阈值外 /
     * 图内无数据」（正常空）区分开；原同宽窗口形态无法区分二者（复核实证会误导调参）。
     *
     * <p>调用纪律：<b>仅在最终召回为空时调用</b>（多一次往返，命中路径零成本）；
     * 失败语义与检索路径同形（异常上抛由调用方按单路容错降级）。
     * 空租户返回零读数（fail-closed 读守卫同形）。
     *
     * @param entityFetchLimit 与 {@link #retrieveChunks} 同值的过取条数（检索窗口可比）
     * @param wideFetchLimit   放大窗口条数（≥ {@code entityFetchLimit}；判「本租户是否确有
     *                         阈值内实体」用）
     */
    GraphRecords.GraphRetrievalDiagnostics diagnoseRetrieval(String tenantId,
                                                             float[] queryEmbedding,
                                                             int entityFetchLimit,
                                                             int wideFetchLimit,
                                                             double similarityThreshold);

    /**
     * 运维观测：租户域实体/关系/锚点计数（回填任务与 E2E 核验用）。
     *
     * <p><b>{@code chunkAnchors} 口径（v3.02 起，v3.03 标注）</b>：锚点镜像 PG <b>全量</b>
     * chunk，故该计数<b>含软删与不可抽取片段</b>的锚点——它既不等于「可图路召回的 chunk 数」
     * （那还要求锚点未被软删且有 MENTIONS 边），也不同于 v3.01 前的「含实体 chunk 数」；
     * 跨版本读数不可直接比较（E2E 核验须同版本对照）。
     */
    GraphCounts countByTenant(String tenantId);

    /**
     * 二跳实体链采样（Phase5簇④ 批4，多跳测试集草稿工具专用）：
     * a→b→c 关系链 + 链首/链尾实体关联的存活 chunk ID——多跳题即
     * 「经 b 桥接 a 与 c」的跨片段推理，本方法产出出题真值材料。
     * 空租户返回空列表（读路径守卫同形）。
     *
     * <p><b>有界采样与确定性</b>（v3.02）：原形态先枚举租户全域 a→b→c 三元组再去重截断
     * （{@code Expand×2 → Distinct → Limit}，计划实证），大租户/高连接度图下中间结果
     * 无界，且 {@code LIMIT} 无 {@code ORDER BY} 使多次采样结果不一致（出题材料不可复现）。
     * 现改为<b>先按 id 序有界采样链首</b>（{@code seedLimit}）再展开，并对链三元组显式
     * 排序——工作量收敛为 seedLimit × 平均度数²，输出可复现。
     *
     * @param limit     链样本上限
     * @param seedLimit 链首采样上限（须 ≥ limit；建议 limit 的 3-5 倍）
     */
    List<GraphRecords.EntityChainSample> sampleEntityChains(String tenantId, int limit, int seedLimit);

    /** 租户域图规模计数 */
    record GraphCounts(long entities, long relations, long chunkAnchors) {
    }
}
