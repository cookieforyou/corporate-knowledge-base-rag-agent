package com.enterprise.kb.domain.enums;

/**
 * 文档图谱构建状态（Phase 5 簇④ GraphRAG，V2 迁移）。
 *
 * <p>知识图谱抽取是 ETL 成功后的异步旁路管道——抽取成败不影响文档入库主状态
 * （{@link DocumentStatus}），独立状态机追踪每文档的图覆盖形态，供回填任务
 * 选目标（PENDING/FAILED）与运维观测。
 */
public enum GraphStatus {
    /** 待抽取：新入库文档默认态；图功能启用后由抽取管道/回填任务消费 */
    PENDING,
    /** 抽取中：管道已领取（并发守卫，防重复抽取） */
    EXTRACTING,
    /** 抽取完成：实体/关系已写图（部分 chunk 抽取失败仍记 COMPLETED，失败计数走指标） */
    COMPLETED,
    /** 抽取失败：管道级故障（写图失败/限流耗尽等），可经回填任务重试 */
    FAILED
}
