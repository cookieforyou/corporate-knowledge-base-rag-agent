package com.enterprise.kb.etl.pipeline.graph;

/**
 * 图谱抽取观测 SPI（簇④，评审修正 R1 依赖倒置）。
 *
 * <p>抽取计数指标族（{@code rag.graph.extraction.*}）归属 {@code AiBusinessMetrics}
 * （kb-ai-core），而抽取服务在 kb-etl——模块不可见，故本接口定义在 kb-etl，
 * 实现由上层模块提供（kb-api 委派 AiBusinessMetrics，对齐 {@code ReindexGateway}
 * 「下游定义接口 / kb-api 实现」先例）。无实现时服务侧静默跳过（缺省方法无操作）。
 */
public interface GraphExtractionListener {

    /** 抽取开始（chunk 数 = 存活非图片候选量） */
    default void extractionStarted(String tenantId, String docId, int chunkCount) {
    }

    /** 抽取成功（实体/关系 = 实际写图量） */
    default void extractionSucceeded(String tenantId, String docId, int entityCount, int relationCount) {
    }

    /** 抽取失败（管道级故障；单 chunk 失败不触发本回调） */
    default void extractionFailed(String tenantId, String docId, String reason) {
    }
}
