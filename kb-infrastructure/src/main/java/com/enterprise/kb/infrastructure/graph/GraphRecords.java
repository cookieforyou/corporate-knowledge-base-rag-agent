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
     * 图路检索规格（v3.02 参数对象化 / v3.04 补邻域上限）：调优参数语义相邻
     * （种子上限/过取条数/候选上限/单种子邻域上限/结果上限），位置参数易错，故收敛为显式规格。
     *
     * @param entityTopN       租户过滤后的种子实体上限
     * @param entityFetchLimit 向量索引取候选条数（≥ entityTopN；过取补偿跨租户名额挤占）
     * @param expandDirection  邻域展开方向（NONE = 不展开；模型为有向，召回取舍见接口 javadoc）
     * @param candidateLimit   展开后候选实体总量上限（种子恒在，仅截断低贡献邻居）
     * @param neighborLimit    单个种子的邻居采样上限（v3.04，按 {@code mention_count} 降序取，
     *                         id 兜底——邻域收集本身有界；{@code <=0} 表示未配置，
     *                         网关回落 {@code candidateLimit}）
     * @param limit            chunk 结果上限
     */
    public record GraphRetrievalSpec(
        float[] queryEmbedding,
        int entityTopN,
        int entityFetchLimit,
        double similarityThreshold,
        ExpandDirection expandDirection,
        int candidateLimit,
        int neighborLimit,
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
     * 图路召回归因读数（空路径诊断专用，v3.03 复核修正 F3）。
     *
     * <p><b>为何要两个窗口</b>：原读数只在「与检索同宽的窗口」内取数，结构上无法区分
     * 「本租户种子被他租户挤占」与「本租户实体全在阈值之外」——后者放大窗口也永远为 0
     * （复核实证：某租户仅一条 0.5 分实体、阈值 0.7，窗口放大到 1000 仍判饿死），
     * 会把运维带向「调大过取倍数」的无效调参。故诊断从<b>放大窗口</b>判「本租户是否确有
     * 阈值内实体」，再看它是否挤进了<b>检索窗口</b>。
     *
     * @param wideCandidates      放大窗口内阈值内候选总数（他租户 + 本租户）
     * @param tenantSeeds         放大窗口内本租户阈值内实体数（&gt;0 = 本租户确有相关实体）
     * @param tenantSeedsInWindow 检索窗口内本租户阈值内实体数（0 = 一个名额都没拿到）
     */
    public record GraphRetrievalDiagnostics(int wideCandidates, int tenantSeeds,
                                            int tenantSeedsInWindow) {

        /** 冷租户零读数（无租户/无向量守卫返回） */
        public static final GraphRetrievalDiagnostics EMPTY =
            new GraphRetrievalDiagnostics(0, 0, 0);

        /**
         * 种子被跨租户后过滤饿死：<b>本租户确有阈值内实体（放大窗口可见）却挤不进检索窗口</b>。
         * 该判据下「调大过取倍数」才是有依据的动作（冷租户 / 全在阈值外不再误报）。
         */
        public boolean starved() {
            return tenantSeeds > 0 && tenantSeedsInWindow == 0;
        }

        /** 本租户在放大窗口内无阈值内实体：空召回正常（冷租户或查询与图无关联），不计饿死 */
        public boolean coldTenant() {
            return tenantSeeds == 0;
        }

        /** 本租户有阈值内种子进了窗口却召回为空——锚点链路缺口而非检索参数问题 */
        public boolean anchorGap() {
            return tenantSeedsInWindow > 0;
        }
    }
}
