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

    /** Chunk 锚点节点（图内不存内容，PG 为事实源，仅存反查所需最小字段） */
    public record ChunkAnchor(String id, int chunkIndex) {
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
