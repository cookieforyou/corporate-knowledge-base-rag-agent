package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.etl.pipeline.graph.GraphExtractionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 图谱抽取观测 SPI 实现（簇④，评审修正 R1 依赖倒置）：
 * kb-etl 定义 {@link GraphExtractionListener}，kb-api 实现并委派
 * {@link AiBusinessMetrics}（{@code rag.graph.extraction.*} 指标族）——
 * 对齐 {@code ReindexGateway}「下游定义接口 / kb-api 实现」先例。
 *
 * <p>缺省关：与图谱域全族同条件装配，关闭态 Bean 缺位，
 * {@code GraphExtractionService} 经 ObjectProvider 容忍（零观测开销）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class MetricsGraphExtractionListener implements GraphExtractionListener {

    private final AiBusinessMetrics metrics;

    public MetricsGraphExtractionListener(AiBusinessMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void extractionStarted(String tenantId, String docId, int chunkCount) {
        log.debug("图谱抽取开始: docId={}, chunks={}", docId, chunkCount);
    }

    @Override
    public void extractionSucceeded(String tenantId, String docId, int entityCount, int relationCount) {
        metrics.recordGraphExtraction(true);
    }

    @Override
    public void extractionFailed(String tenantId, String docId, String reason) {
        metrics.recordGraphExtraction(false);
    }
}
