package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;
import com.enterprise.kb.admin.gateway.ReindexGateway;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IndexRebuildService 单测（Phase 4 簇③ 4.5）——重建编排：全量/目标模式、
 * 终态汇聚计数、ES 孤儿清扫 diff、同步快速失败 skipped 语义。
 * 执行器注入 Runnable::run 直跑形态，start() 同步完成任务便于断言。
 * 任务表经内存 fake（{@link InMemoryTaskStore}）隔离，Redis 形态回归见
 * RedisRebuildTaskStoreTest / kb-eval RedisRebuildTaskStoreIT。
 */
class IndexRebuildServiceTest {

    private static final String TENANT = "t-1";

    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private ReindexGateway reindexGateway;
    private EsIndexWriter esIndexWriter;
    private InMemoryTaskStore taskStore;
    private IndexRebuildService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        reindexGateway = mock(ReindexGateway.class);
        esIndexWriter = mock(EsIndexWriter.class);
        taskStore = new InMemoryTaskStore();
        service = new IndexRebuildService(documentRepository, chunkRepository,
            reindexGateway, esIndexWriter, Runnable::run, taskStore);
        ReflectionTestUtils.setField(service, "concurrency", 2);
        ReflectionTestUtils.setField(service, "docTimeoutMinutes", 5L);
    }

    private static KbDocument doc(String id, DocumentStatus status) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setTenantId(TENANT);
        doc.setStatus(status);
        return doc;
    }

    private static KbChunk chunk(String id) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        return chunk;
    }

    @Test
    void fullRebuildTalliesOutcomesAndCompletesTask() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq(TENANT), anyList()))
            .thenReturn(List.of(doc("d-1", DocumentStatus.SUCCESS), doc("d-2", DocumentStatus.FAILED)));
        when(reindexGateway.reparse("d-1", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(reindexGateway.reparse("d-2", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(false));
        when(chunkRepository.findByDocIdOrderByChunkIndex(anyString())).thenReturn(List.of());
        when(esIndexWriter.findChunkIdsByDocId(anyString())).thenReturn(List.of());

        RebuildTaskView started = service.start(TENANT, null);
        RebuildTaskView view = service.detail(TENANT, started.taskId());

        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.total()).isEqualTo(2);
        assertThat(view.succeeded()).isEqualTo(1);
        assertThat(view.failed()).isEqualTo(1);
        assertThat(view.skipped()).isZero();
        assertThat(view.finishedAt()).isNotNull();
        assertThat(view.failures()).hasSize(1);
        assertThat(view.failures().get(0).docId()).isEqualTo("d-2");
    }

    /** 重建成功文档触发 ES 孤儿清扫：ES 有而 PG 无 → 精确删；PG 存活集含软删行不误删 */
    @Test
    void successfulDocSweepsEsOrphansByDiff() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq(TENANT), anyList()))
            .thenReturn(List.of(doc("d-1", DocumentStatus.SUCCESS)));
        when(reindexGateway.reparse("d-1", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d-1"))
            .thenReturn(List.of(chunk("c-1"), chunk("c-2")));
        when(esIndexWriter.findChunkIdsByDocId("d-1"))
            .thenReturn(List.of("c-1", "c-2", "c-orphan"));

        service.start(TENANT, null);

        verify(esIndexWriter).deleteByChunkIds(List.of("c-orphan"));
    }

    /** 重建失败文档不触发孤儿清扫（新数据未写入，清扫可能误删旧数据） */
    @Test
    void failedDocSkipsOrphanSweep() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq(TENANT), anyList()))
            .thenReturn(List.of(doc("d-1", DocumentStatus.SUCCESS)));
        when(reindexGateway.reparse("d-1", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(false));

        service.start(TENANT, null);

        verify(esIndexWriter, never()).findChunkIdsByDocId(anyString());
        verify(esIndexWriter, never()).deleteByChunkIds(any());
    }

    @Test
    void targetedRebuildSkipsCrossTenantAndProcessingDocs() {
        KbDocument crossTenant = doc("d-other", DocumentStatus.SUCCESS);
        crossTenant.setTenantId("t-other");
        when(documentRepository.findById("d-other")).thenReturn(Optional.of(crossTenant));
        when(documentRepository.findById("d-busy")).thenReturn(Optional.of(doc("d-busy", DocumentStatus.PARSING)));
        when(documentRepository.findById("d-1")).thenReturn(Optional.of(doc("d-1", DocumentStatus.SUCCESS)));
        when(reindexGateway.reparse("d-1", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(chunkRepository.findByDocIdOrderByChunkIndex(anyString())).thenReturn(List.of());
        when(esIndexWriter.findChunkIdsByDocId(anyString())).thenReturn(List.of());

        RebuildTaskView started = service.start(TENANT, List.of("d-other", "d-busy", "d-1"));

        assertThat(started.total()).isEqualTo(1);
        assertThat(started.skipped()).isEqualTo(2);
        assertThat(started.failures()).extracting(FailureView::docId)
            .containsExactly("d-other", "d-busy");
        verify(reindexGateway).reparse("d-1", TENANT, null);
        verify(reindexGateway, never()).reparse(eq("d-other"), anyString(), any());
    }

    /** 网关同步快速失败（占用竞态/不存在）→ skipped，任务不中断 */
    @Test
    void gatewaySyncFailureRecordedAsSkipped() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq(TENANT), anyList()))
            .thenReturn(List.of(doc("d-1", DocumentStatus.SUCCESS), doc("d-2", DocumentStatus.SUCCESS)));
        when(reindexGateway.reparse("d-1", TENANT, null))
            .thenThrow(new BusinessException("DOC_NOT_READY", "并发占用"));
        when(reindexGateway.reparse("d-2", TENANT, null))
            .thenReturn(CompletableFuture.completedFuture(true));
        when(chunkRepository.findByDocIdOrderByChunkIndex(anyString())).thenReturn(List.of());
        when(esIndexWriter.findChunkIdsByDocId(anyString())).thenReturn(List.of());

        RebuildTaskView view = service.start(TENANT, null);

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.succeeded()).isEqualTo(1);
        assertThat(view.skipped()).isEqualTo(1);
    }

    @Test
    void taskRegistryDetailAndList() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq(TENANT), anyList()))
            .thenReturn(List.of());

        RebuildTaskView started = service.start(TENANT, null);

        assertThat(service.detail(TENANT, started.taskId())).isNotNull();
        assertThat(service.detail(TENANT, "unknown-task")).isNull();
        // v2.36 租户收敛：跨租户详情不可见（同不存在语义）
        assertThat(service.detail("t-other", started.taskId())).isNull();
        assertThat(service.list(TENANT)).extracting(RebuildTaskView::taskId).contains(started.taskId());
        assertThat(service.list("t-other")).isEmpty();
    }

    /**
     * 内存 fake 任务表——实现 RebuildTaskStore 契约（insertion order + 租户
     * 收敛 + 计数语义与 Redis 形态一致），隔离编排测试与存储形态。
     */
    static final class InMemoryTaskStore implements RebuildTaskStore {

        private final Map<String, RebuildTaskView> tasks = new LinkedHashMap<>();

        @Override
        public void create(String tenantId, String taskId, int total, List<FailureView> initialSkipped) {
            tenants.put(taskId, tenantId);
            tasks.put(taskId, new RebuildTaskView(taskId, "RUNNING", total, 0, 0,
                initialSkipped.size(), LocalDateTime.now(), null,
                new ArrayList<>(initialSkipped)));
        }

        @Override
        public void recordSuccess(String taskId) {
            mutate(taskId, v -> new RebuildTaskView(v.taskId(), v.status(), v.total(),
                v.succeeded() + 1, v.failed(), v.skipped(), v.startedAt(), v.finishedAt(), v.failures()));
        }

        @Override
        public void recordFailure(String taskId, String docId, String reason) {
            mutate(taskId, v -> {
                List<FailureView> failures = new ArrayList<>(v.failures());
                failures.add(new FailureView(docId, reason));
                return new RebuildTaskView(v.taskId(), v.status(), v.total(),
                    v.succeeded(), v.failed() + 1, v.skipped(), v.startedAt(), v.finishedAt(), failures);
            });
        }

        @Override
        public void recordSkipped(String taskId, String docId, String reason) {
            mutate(taskId, v -> {
                List<FailureView> failures = new ArrayList<>(v.failures());
                failures.add(new FailureView(docId, reason));
                return new RebuildTaskView(v.taskId(), v.status(), v.total(),
                    v.succeeded(), v.failed(), v.skipped() + 1, v.startedAt(), v.finishedAt(), failures);
            });
        }

        @Override
        public void finish(String taskId) {
            mutate(taskId, v -> new RebuildTaskView(v.taskId(), "COMPLETED", v.total(),
                v.succeeded(), v.failed(), v.skipped(), v.startedAt(), LocalDateTime.now(), v.failures()));
        }

        @Override
        public Optional<RebuildTaskView> find(String taskId, String requiredTenantId) {
            if (!requiredTenantId.equals(tenants.get(taskId))) {
                return Optional.empty();
            }
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public List<RebuildTaskView> listByTenant(String tenantId) {
            return tasks.entrySet().stream()
                .filter(e -> tenantId.equals(tenants.get(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
        }

        private final Map<String, String> tenants = new LinkedHashMap<>();

        private void mutate(String taskId, java.util.function.UnaryOperator<RebuildTaskView> fn) {
            RebuildTaskView view = tasks.get(taskId);
            if (view != null) {
                tasks.put(taskId, fn.apply(view));
            }
        }
    }
}
