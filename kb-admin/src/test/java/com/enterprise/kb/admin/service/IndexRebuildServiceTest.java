package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
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

import java.util.List;
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
 */
class IndexRebuildServiceTest {

    private static final String TENANT = "t-1";

    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private ReindexGateway reindexGateway;
    private EsIndexWriter esIndexWriter;
    private IndexRebuildService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        reindexGateway = mock(ReindexGateway.class);
        esIndexWriter = mock(EsIndexWriter.class);
        service = new IndexRebuildService(documentRepository, chunkRepository,
            reindexGateway, esIndexWriter, Runnable::run);
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
        RebuildTaskView view = service.detail(started.taskId());

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
        assertThat(started.failures()).extracting(RebuildTaskView.FailureView::docId)
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

        assertThat(service.detail(started.taskId())).isNotNull();
        assertThat(service.detail("unknown-task")).isNull();
        assertThat(service.list()).extracting(RebuildTaskView::taskId).contains(started.taskId());
    }
}
