package com.enterprise.kb.etl.pipeline.graph;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图谱抽取异步派发器（簇④ 5.1）：知识库内容提交点调用，虚拟线程异步执行
 * {@link GraphExtractionService}——<b>抽取是旁路管道，不阻塞调用方主流程</b>
 * （ETL 终态帧 / 回填任务经本入口统一派发）。
 *
 * <p><b>接线点（批2 + 5.1 热修）</b>：kb-api {@code DocumentService} 两处
 * COMPLETED 终态帧——重入库进度回调（reparse / replace / 索引重建，
 * 重建委派 reparse 同路径不重复接线）+ 首次入库 upload 回调（批2 漏接，
 * 热修补齐），同语义缓存失效发布器旁位。
 *
 * <p><b>fail-open 纪律</b>：派发/抽取故障仅 warn——图谱是检索增强件，
 * 不得击穿文档入库/运维主流程（{@code GraphStatus.FAILED} 留痕，
 * 可经回填任务重试收敛）。
 *
 * <p><b>缺省关</b>：与图谱域全族同条件装配——关闭态 Bean 缺位，
 * 接线侧经 {@code ObjectProvider} 容忍（零派发开销，链形态零变化）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphExtractionPublisher {

    private final GraphExtractionService extractionService;
    /** 抽取派发执行器（虚拟线程，Bean 单例持有——执行器纪律同 hybridRetrievalExecutor） */
    private final ExecutorService extractionExecutor;

    public GraphExtractionPublisher(GraphExtractionService extractionService) {
        this.extractionService = extractionService;
        this.extractionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 异步派发单文档抽取；参数缺失静默返回（对齐缓存失效发布器守卫） */
    public void publish(String tenantId, String docId) {
        if (tenantId == null || tenantId.isBlank() || docId == null || docId.isBlank()) {
            return;
        }
        try {
            extractionExecutor.submit(() -> {
                try {
                    extractionService.extract(tenantId, docId);
                } catch (Exception e) {
                    log.warn("图谱抽取异步执行异常（FAILED 状态已留痕，可经回填重试）: docId={}, {}",
                        docId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("图谱抽取派发失败（不阻断主流程）: docId={}, {}", docId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        extractionExecutor.close();
    }
}
