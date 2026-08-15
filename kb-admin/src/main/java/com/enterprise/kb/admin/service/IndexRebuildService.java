package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;
import com.enterprise.kb.admin.gateway.ReindexGateway;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 索引重建服务（Phase 4 簇③ 4.5）——全量/目标文档重建编排，复用 C1 蓝绿管线。
 *
 * <p><b>重建语义</b>：逐文档经 {@link ReindexGateway} 委派 kb-api
 * DocumentService.reparse（原子占用 + 蓝绿「全量写入 → diff 清理」+ 版本号递增）——
 * PG 事实源驱动的三库收敛：存活 chunk 同 ID 幂等覆写向量库/ES，「旧有新无」
 * 经 diff 精确清除。单文档重嵌入失败不阻断任务（记 failed 继续）。
 *
 * <p><b>ES 孤儿清扫</b>（漂移修复收敛增量）：蓝绿 diff 仅覆盖 PG 新旧集，
 * 不清除「PG 已无行而 ES 残留」的孤儿 doc（部分失败的历史残留）。每文档重建
 * 成功后追加一步 ES diff 清扫：查 ES doc_id 全集 − PG 存活集 → deleteByChunkIds
 * 精确删（不走 deleteByDocId 全清，避免误删刚重写的存活 doc）。
 * 向量库孤儿不在本簇范围：双向量库列表 API 异构（pgvector SQL / Milvus query），
 * 产生条件仅「清理期向量删除失败」罕见路径，留离线清理（9.4 一致性模型既定）。
 *
 * <p><b>任务表形态（v2.36）</b>：迁 Redis（{@link RedisRebuildTaskStore}）——
 * 重启保留（TTL 窗口内）、租户域隔离（v2.33 内存表任务列表全局共享、跨租户
 * 可见失败明细 docId 的收敛）、受理实例执行 + 任意实例轮询；任务执行仍在
 * 受理实例（执行中实例宕机则任务残留 RUNNING 至 TTL，重建幂等可重发）。
 *
 * <p><b>并发控制</b>：滑动窗口 {@code rag.admin.rebuild.concurrency}（默认 4）——
 * ETL 瓶颈在 embedding 批量调用（10 条/批供应商硬限制），窗口并发摊薄串行时延；
 * 10 万 chunk 重建时延外推见第 14 章 v2.33 验收论证。
 */
@Slf4j
@Service
public class IndexRebuildService {

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final ReindexGateway reindexGateway;
    private final EsIndexWriter esIndexWriter;
    private final Executor etlExecutor;
    private final RebuildTaskStore taskStore;

    @Value("${rag.admin.rebuild.concurrency:4}")
    private int concurrency;

    @Value("${rag.admin.rebuild.doc-timeout-minutes:30}")
    private long docTimeoutMinutes;

    public IndexRebuildService(KbDocumentRepository documentRepository,
                               KbChunkRepository chunkRepository,
                               ReindexGateway reindexGateway,
                               EsIndexWriter esIndexWriter,
                               @Qualifier("etlExecutor") Executor etlExecutor,
                               RebuildTaskStore taskStore) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.reindexGateway = reindexGateway;
        this.esIndexWriter = esIndexWriter;
        this.etlExecutor = etlExecutor;
        this.taskStore = taskStore;
    }

    /**
     * 发起重建任务：docIds 空 = 租户全量（SUCCESS + FAILED，FAILED 重入库是
     * 正当修复路径）；指定 = 目标文档增量（越权/处理中前置记 skipped）。
     * 任务表登记 fail-closed（Redis 故障 → REBUILD_STORE_UNAVAILABLE，不无表启动）。
     */
    public RebuildTaskView start(String tenantId, List<String> docIds) {
        List<KbDocument> targets = new ArrayList<>();
        List<FailureView> preSkipped = new ArrayList<>();

        if (docIds == null || docIds.isEmpty()) {
            targets.addAll(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
                tenantId, List.of(DocumentStatus.SUCCESS, DocumentStatus.FAILED)));
        } else {
            for (String docId : docIds.stream().distinct().toList()) {
                KbDocument doc = documentRepository.findById(docId).orElse(null);
                if (doc == null || !tenantId.equals(doc.getTenantId())) {
                    preSkipped.add(new FailureView(docId, "文档不存在或无权访问"));
                    continue;
                }
                if (doc.getStatus() != null && doc.getStatus().isProcessing()) {
                    preSkipped.add(new FailureView(docId, "文档处理中，不可重入库（" + doc.getStatus() + "）"));
                    continue;
                }
                targets.add(doc);
            }
        }

        String taskId = UUID.randomUUID().toString();
        taskStore.create(tenantId, taskId, targets.size(), preSkipped);
        log.info("索引重建任务已创建: taskId={}, mode={}, targets={}, preSkipped={}",
            taskId, docIds == null || docIds.isEmpty() ? "FULL" : "TARGETED",
            targets.size(), preSkipped.size());

        etlExecutor.execute(() -> runTask(taskId, targets, tenantId));
        return taskStore.find(taskId, tenantId).orElse(null);
    }

    /** 任务详情：不存在/跨租户返回 null（Controller 层转 REBUILD_TASK_NOT_FOUND） */
    public RebuildTaskView detail(String tenantId, String taskId) {
        return taskStore.find(taskId, tenantId).orElse(null);
    }

    /** 租户任务列表（insertion order，最新在后，近 20 条 FIFO） */
    public List<RebuildTaskView> list(String tenantId) {
        return taskStore.listByTenant(tenantId);
    }

    // ── 内部方法 ──

    /** 重建主循环：滑动窗口并发 + 单文档终态汇聚 + ES 孤儿清扫 */
    private void runTask(String taskId, List<KbDocument> targets, String tenantId) {
        Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
        int window = Math.max(1, concurrency);
        // 本地镜像计数仅供完成日志（任务表事实源在 Redis）
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();

        for (KbDocument doc : targets) {
            while (pending.size() >= window) {
                awaitAny(pending);
                drainCompleted(pending);
            }
            CompletableFuture<Boolean> future;
            try {
                future = reindexGateway.reparse(doc.getId(), tenantId, null);
            } catch (Exception e) {
                taskStore.recordSkipped(taskId, doc.getId(), e.getMessage());
                skipped.incrementAndGet();
                continue;
            }
            String docId = doc.getId();
            pending.put(docId, future
                .orTimeout(docTimeoutMinutes, TimeUnit.MINUTES)
                .whenComplete((ok, ex) -> {
                    if (ex != null) {
                        taskStore.recordFailure(taskId, docId, "重入库异常: " + ex.getMessage());
                        failed.incrementAndGet();
                    } else if (Boolean.TRUE.equals(ok)) {
                        sweepEsOrphans(docId);
                        taskStore.recordSuccess(taskId);
                        succeeded.incrementAndGet();
                    } else {
                        taskStore.recordFailure(taskId, docId, "ETL 重入库失败（见文档 error_message）");
                        failed.incrementAndGet();
                    }
                }));
        }
        while (!pending.isEmpty()) {
            awaitAny(pending);
            drainCompleted(pending);
        }

        taskStore.finish(taskId);
        log.info("索引重建任务完成: taskId={}, total={}, succeeded={}, failed={}, skipped={}",
            taskId, targets.size(), succeeded.get(), failed.get(), skipped.get());
    }

    /** 等待窗口内任一 future 终态（异常吞掉——结果已由 whenComplete 汇聚） */
    private static void awaitAny(Map<String, CompletableFuture<Boolean>> pending) {
        CompletableFuture<?>[] futures = pending.values().toArray(new CompletableFuture<?>[0]);
        if (futures.length == 0) {
            return;
        }
        CompletableFuture.anyOf(futures).handle((r, e) -> null).join();
    }

    /**
     * 移除已终态条目——主循环驱动的统一出口。不经 whenComplete 移除：
     * 已完成 future 的 whenComplete 先于 put 执行，其内移除为空操作，
     * 残留的已完成条目会使 awaitAny 空转死循环（竞态修复，实现期实证）。
     */
    private static void drainCompleted(Map<String, CompletableFuture<Boolean>> pending) {
        pending.entrySet().removeIf(e -> e.getValue().isDone());
    }

    /**
     * ES 孤儿清扫：ES doc_id 全集 − PG 现存集（含软删行——其 ES doc 带
     * is_deleted=true 标记属合法存在）→ 精确删。尽力而为不阻断任务。
     */
    private void sweepEsOrphans(String docId) {
        try {
            Set<String> pgIds = chunkRepository.findByDocIdOrderByChunkIndex(docId)
                .stream().map(KbChunk::getId).collect(Collectors.toSet());
            List<String> orphans = esIndexWriter.findChunkIdsByDocId(docId)
                .stream().filter(id -> !pgIds.contains(id)).toList();
            if (!orphans.isEmpty()) {
                esIndexWriter.deleteByChunkIds(orphans);
                log.info("重建 ES 孤儿清扫: docId={}, orphans={}", docId, orphans.size());
            }
        } catch (Exception e) {
            log.warn("重建 ES 孤儿清扫失败（不阻断）: docId={}, {}", docId, e.getMessage());
        }
    }
}
