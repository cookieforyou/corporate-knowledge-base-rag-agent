package com.enterprise.kb.infrastructure.graph;

import java.util.List;

/**
 * 图谱网关数据契约（Phase5簇④）。
 *
 * <p>全部为纯数据 record，不含 Neo4j 类型——上层（kb-etl 抽取 / kb-ai-core 检索）
 * 只消费这些契约，Cypher 细节封闭在 {@link Neo4jGraphGateway} 内。
 */
public final class GraphRecords {

    private GraphRecords() {
    }

    /** 实体写入请求：id 经 {@link GraphIds#entityId} 派生（租户 × 名称 × 类型确定性） */
    public record EntityWrite(
        String id,
        String name,
        String type,
        String description,
        float[] embedding,
        List<String> chunkIds) {
    }

    /** 关系写入请求：源/目标实体 id + 关系类型；幂等键 = (源, 目标, 类型) */
    public record RelationWrite(
        String sourceId,
        String targetId,
        String relationType,
        String description,
        List<String> chunkIds) {
    }

    /**
     * Chunk 锚点节点（图内不存内容，PG 为事实源，仅存反查所需最小字段）。
     *
     * <p>{@code isDeleted}（v3.02）：锚点集合镜像 <b>PG 全量 chunk</b>（含软删与不可抽取
     * 片段）——原形态只写"含实体 chunk"，而幂等重写阶段一会删除该文档全部锚点，
     * 故软删期间发生过重抽取的 chunk 会永久失去锚点（之后 restore 翻转不到任何节点，
     * 该 chunk 的图路召回静默丢失直到再次重抽取）。带标记全量写入后，锚点生命周期
     * 与抽取结果解耦（软删 = 标记，恢复 = 翻回）。
     */
    public record ChunkAnchor(String id, int chunkIndex, boolean isDeleted) {
    }

    /**
     * 图路检索规格（v3.02 参数对象化）：8 项调优参数中四个 int 语义相邻
     * （种子上限/过取条数/候选上限/结果上限），位置参数易错，故收敛为显式规格。
     *
     * @param entityTopN       租户过滤后的种子实体上限
     * @param entityFetchLimit 向量索引取候选条数（≥ entityTopN；过取补偿跨租户名额挤占）
     * @param expandDirection  邻域展开方向（NONE = 不展开；模型为有向，召回取舍见接口 javadoc）
     * @param candidateLimit   展开后候选实体总量上限（种子恒在，仅截断低贡献邻居）
     * @param limit            chunk 结果上限
     */
    public record GraphRetrievalSpec(
        float[] queryEmbedding,
        int entityTopN,
        int entityFetchLimit,
        double similarityThreshold,
        ExpandDirection expandDirection,
        int candidateLimit,
        int limit) {
    }

    /**
     * 邻域展开方向（v3.02）：图模型为<b>有向</b>（抽取提示词产出 {@code WORKS_AT /
     * PART_OF / DEPENDS_ON / PRODUCED_BY} 等方向性类型），而展开是召回机制而非逻辑推理——
     * 缺省 {@link #BOTH} 保持既有召回行为（双向），需方向敏感时切 {@link #OUTGOING} /
     * {@link #INCOMING} 并以多跳集 A/B 定调。
     */
    public enum ExpandDirection {
        /** 不展开（仅种子实体） */
        NONE,
        /** 双向（缺省，保持 v3.00 前既有召回行为） */
        BOTH,
        /** 仅出边（种子 → 邻居，语义方向一致） */
        OUTGOING,
        /** 仅入边（邻居 → 种子） */
        INCOMING
    }

    /** 图路检索命中：chunk 反查结果 + 溯源元数据（实体命中名/跳数，供 TRACE 与调试台） */
    public record GraphChunkHit(
        String chunkId,
        String docId,
        double score,
        List<String> entityNames,
        int hop) {
    }

    /** 二跳实体链样本（多跳测试集草稿材料）：实体名链 a→b→c + 链首/尾关联存活 chunk */
    public record EntityChainSample(
        List<String> entityNames,
        List<String> chunkIds) {
    }

    /**
     * 图路召回归因读数（空路径诊断专用）：向量索引窗口内候选数 + 其中本租户种子数
     * + 本租户图谱是否有数据。
     *
     * <p>三个读数足以区分空召回成因（v2.85 实证）：
     * <ul>
     *   <li>{@code indexCandidates = 0}——窗口内无阈值内近邻实体（查询与图无关联，正常空）；</li>
     *   <li>{@link #starved()}——窗口内有他租户近邻、本租户零种子<b>且本租户图谱有数据</b>
     *       （索引过取倍数不足，调 {@code spring.neo4j.entity-over-fetch}）；</li>
     *   <li>{@link #coldTenant()}——本租户图谱无任何实体（未抽取/未回填，空召回正常，
     *       不计饿死——否则「图里没有」会污染饿死指标）；</li>
     *   <li>{@link #anchorGap()}——本租户有阈值内种子实体却召回为空（抽取/锚点链路缺口，
     *       非检索参数问题）。</li>
     * </ul>
     */
    public record GraphRetrievalDiagnostics(int indexCandidates, int tenantSeeds, boolean tenantHasGraph) {

        /** 冷租户零读数（无租户/无向量守卫返回） */
        public static final GraphRetrievalDiagnostics EMPTY =
            new GraphRetrievalDiagnostics(0, 0, false);

        /** 种子被跨租户后过滤饿死：窗口内有候选，本租户一个都没进，且本租户图谱确有数据 */
        public boolean starved() {
            return indexCandidates > 0 && tenantSeeds == 0 && tenantHasGraph;
        }

        /** 本租户图谱无数据：空召回属正常（不计饿死，避免冷租户污染指标） */
        public boolean coldTenant() {
            return !tenantHasGraph;
        }

        /** 本租户有阈值内种子实体却召回为空——锚点链路缺口而非检索参数问题 */
        public boolean anchorGap() {
            return tenantSeeds > 0;
        }
    }
}
