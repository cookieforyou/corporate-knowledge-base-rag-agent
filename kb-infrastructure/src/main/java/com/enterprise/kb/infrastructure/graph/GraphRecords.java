package com.enterprise.kb.infrastructure.graph;

import java.util.List;

/**
 * 图谱网关数据契约（Phase 5 簇④）。
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
}
