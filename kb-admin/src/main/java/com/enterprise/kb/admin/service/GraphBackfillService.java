package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GraphBackfillView;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.GraphStatus;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.graph.GraphExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图谱专项回填任务（簇④ 5.1 批3，用户定案形态）：存量语料首次建图——
 * <b>直读 PG 存量 chunk，跳过解析/嵌入，只走抽取 → 写图</b>（存量文档已有
 * 解析与向量结果，经重建端点回填会白付解析 + 嵌入费用）。
 *
 * <p>编排模式沿用 {@code IndexRebuildService}：滑动窗口并发 + 单文档终态汇聚；
 * 差异在单文档执行体 = {@link GraphExtractionService#extract} 同步调用
 * （其内部已有令牌桶 + 信号量双限流，窗口并发仅控制在飞文档数）。
 *
 * <p><b>选目标口径</b>：{@code docIds} 缺省 = 租户全量待回填——入库成功
 * （SUCCESS）且 {@code graph_status} 为 PENDING/EXTRACTING/FAILED 者（幂等重跑
 * 天然跳过 COMPLETED；**EXTRACTING 残留** = 进程崩溃中断的收敛兜底——幂等重写
 * 天然收敛，用户侧 E2E 实证 OOM 崩溃遗留，v2.78 补入）；显式 {@code docIds}
 * = 目标增量（越权/状态不符前置记失败明细）。
 *
 * <p><b>单租户单任务</b>：既有回填在途 → 409 {@code GRAPH_BACKFILL_RUNNING}
 * （回填幂等收敛，无需并行多任务）。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphBackfillService {

    private final KbDocumentRepository documentRepository;
    private final ObjectProvider<GraphExtractionService> extractionServiceProvider;
    private final Executor etlExecutor;
    private final RedisGraphBackfillStore store;

    /** 在飞文档窗口——抽取内部已限流（20 次/分/租户），窗口仅控并发面 */
    @Value("${rag.graph.backfill.concurrency:2}")
    private int concurrency;

    @Value("${rag.graph.backfill.doc-timeout-minutes:15}")
    private long docTimeoutMinutes;

    /** 终态轮询窗口（秒）——窗口未走完仅表示抽取在途，续轮询，绝不可置中断标志（坑位㊳） */
    @Value("${rag.graph.backfill.poll-seconds:60}")
    private long pollSeconds;

    public GraphBackfillService(KbDocumentRepository documentRepository,
                                ObjectProvider<GraphExtractionService> extractionServiceProvider,
                                @Qualifier("etlExecutor") Executor etlExecutor,
                                RedisGraphBackfillStore store) {
        this.documentRepository = documentRepository;
        this.extractionServiceProvider = extractionServiceProvider;
        this.etlExecutor = etlExecutor;
        this.store = store;
    }

    /** 发起回填：受理即异步执行，返回任务视图（含前置失败明细计数） */
    public GraphBackfillView start(String tenantId, List<String> docIds) {
        GraphExtractionService extractionService = extractionServiceProvider.getIfAvailable();
        if (extractionService == null) {
            throw new BusinessException("GRAPH_DISABLED", "图谱功能未启用（rag.graph.enabled=false）");
        }
        List<KbDocument> targets = new ArrayList<>();
        AtomicInteger preFailed = new AtomicInteger();

        if (docIds == null || docIds.isEmpty()) {
            documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
                    tenantId, List.of(DocumentStatus.SUCCESS)).stream()
                .filter(doc -> doc.getGraphStatus() == null
                    || doc.getGraphStatus() == GraphStatus.PENDING
                    || doc.getGraphStatus() == GraphStatus.EXTRACTING
                    || doc.getGraphStatus() == GraphStatus.FAILED)
                .forEach(targets::add);
        } else {
            for (String docId : docIds.stream().distinct().toList()) {
                KbDocument doc = documentRepository.findById(docId).orElse(null);
                if (doc == null || !tenantId.equals(doc.getTenantId())) {
                    preFailed.incrementAndGet();   // 不存在/跨租户不泄露存在性，只计数
                    continue;
                }
                if (doc.getStatus() != DocumentStatus.SUCCESS) {
                    preFailed.incrementAndGet();   // 未成功入库无存量 chunk 可抽取
                    continue;
                }
                targets.add(doc);
            }
        }
        if (targets.isEmpty()) {
            return new GraphBackfillView(GraphBackfillView.STATUS_COMPLETED,
                0, 0, preFailed.get(), null, null);
        }
        if (!store.tryStart(tenantId, targets.size())) {
            throw new BusinessException("GRAPH_BACKFILL_RUNNING", "该租户已有图谱回填任务在途");
        }
        log.info("图谱回填任务已受理: tenant={}, targets={}, preFailed={}",
            tenantId, targets.size(), preFailed.get());
        CompletableFuture.runAsync(() -> runTask(tenantId, targets, extractionService, preFailed.get()),
            etlExecutor);
        return store.view(tenantId).orElse(null);
    }

    /** 任务视图（无任务态返回空态视图，前端零判空） */
    public GraphBackfillView status(String tenantId) {
        return store.view(tenantId).orElse(new GraphBackfillView(
            null, 0, 0, 0, null, null));
    }

    /** 回填主循环：滑动窗口并发 + 单文档终态汇聚（模式同重建任务） */
    private void runTask(String tenantId, List<KbDocument> targets,
                         GraphExtractionService extractionService, int preFailed) {
        try {
            Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
            int window = Math.max(1, concurrency);
            Iterator<KbDocument> iterator = targets.iterator();
            while (iterator.hasNext() || !pending.isEmpty()) {
                while (iterator.hasNext() && pending.size() < window) {
                    String docId = iterator.next().getId();
                    pending.put(docId, CompletableFuture
                        .supplyAsync(() -> extractionService.extract(tenantId, docId), etlExecutor)
                        .orTimeout(docTimeoutMinutes, TimeUnit.MINUTES)
                        .exceptionally(ex -> false));
                }
                awaitAny(pending);
                drainCompleted(pending, tenantId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("图谱回填任务被中断（在途抽取独立完成，残留经再次回填收敛）: tenant={}", tenantId);
        } finally {
            // 终态必达收敛——中断亦须落终态，否则 RUNNING 残留至 TTL 过期前持续 409
            // 前置失败（越权/状态不符）不入任务计数——受理前拒绝，非任务内失败
            store.finish(tenantId);
            log.info("图谱回填任务完成: tenant={}, preFailed={}", tenantId, preFailed);
        }
    }

    private void drainCompleted(Map<String, CompletableFuture<Boolean>> pending, String tenantId) {
        pending.entrySet().removeIf(entry -> {
            if (!entry.getValue().isDone()) {
                return false;
            }
            boolean ok = entry.getValue().getNow(false);
            store.recordResult(tenantId, ok);
            return true;
        });
    }

    /**
     * 轮询等待任一文档终态。<b>超时 ≠ 中断</b>（坑位㊳ 实证）：TimeoutException
     * 仅表示轮询窗口未走完——若误置中断标志，任务线程后续每次轮询立即惊醒（热自旋，
     * 异常栈 + anyOf 对象高速分配挤爆堆——用户侧 E2E 大文档回填 OutOfMemoryError
     * 根因），且同线程一切可中断调用（Redisson 计数回写）全线 InterruptedException。
     */
    private void awaitAny(Map<String, CompletableFuture<Boolean>> pending) throws InterruptedException {
        try {
            CompletableFuture.anyOf(pending.values().toArray(CompletableFuture[]::new))
                .get(pollSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // 窗口未走完 = 抽取在途续轮询；ExecutionException 已被 exceptionally 收敛，防御兜底
        }
    }
}
