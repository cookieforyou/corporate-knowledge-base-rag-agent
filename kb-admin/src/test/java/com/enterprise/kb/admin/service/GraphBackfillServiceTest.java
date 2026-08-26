package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GraphBackfillView;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.GraphStatus;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.graph.GraphExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GraphBackfillService 单测（簇④ 批3）：选目标口径 / 单租户单任务守卫 /
 * 幂等收敛（跳过已完成）/ 越权过滤。
 */
class GraphBackfillServiceTest {

    private static final String TENANT = "tenant-a";

    private KbDocumentRepository documentRepository;
    private GraphExtractionService extractionService;
    private RedisGraphBackfillStore store;
    private ObjectProvider<GraphExtractionService> provider;
    private GraphBackfillService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        documentRepository = mock(KbDocumentRepository.class);
        extractionService = mock(GraphExtractionService.class);
        store = mock(RedisGraphBackfillStore.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(extractionService);

        service = new GraphBackfillService(documentRepository, provider,
            (java.util.concurrent.Executor) Runnable::run, store);
        ReflectionTestUtils.setField(service, "concurrency", 1);
        ReflectionTestUtils.setField(service, "docTimeoutMinutes", 1L);
        ReflectionTestUtils.setField(service, "pollSeconds", 1L);
        when(store.tryStart(anyString(), anyInt())).thenReturn(true);
        when(store.view(anyString())).thenReturn(Optional.empty());
    }

    private KbDocument doc(String id, DocumentStatus status, GraphStatus graphStatus) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setTenantId(TENANT);
        doc.setStatus(status);
        doc.setGraphStatus(graphStatus);
        return doc;
    }

    @Test
    void fullModeTargetsPendingFailedAndExtractingResidueSkippingCompleted() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
            eq(TENANT), anyList())).thenReturn(List.of(
            doc("d-pending", DocumentStatus.SUCCESS, GraphStatus.PENDING),
            doc("d-failed", DocumentStatus.SUCCESS, GraphStatus.FAILED),
            doc("d-extracting", DocumentStatus.SUCCESS, GraphStatus.EXTRACTING),
            doc("d-done", DocumentStatus.SUCCESS, GraphStatus.COMPLETED)));
        when(extractionService.extract(anyString(), anyString())).thenReturn(true);

        service.start(TENANT, null);

        verify(extractionService).extract(TENANT, "d-pending");
        verify(extractionService).extract(TENANT, "d-failed");
        verify(extractionService).extract(TENANT, "d-extracting");   // 崩溃残留收敛兜底
        verify(extractionService, never()).extract(TENANT, "d-done");   // 幂等收敛
        verify(store).tryStart(TENANT, 3);
    }

    @Test
    void runningTaskRejectedWith409() {
        when(store.tryStart(anyString(), anyInt())).thenReturn(false);
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
            eq(TENANT), anyList())).thenReturn(List.of(
            doc("d1", DocumentStatus.SUCCESS, GraphStatus.PENDING)));

        assertThatThrownBy(() -> service.start(TENANT, null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo("GRAPH_BACKFILL_RUNNING");
    }

    @Test
    void targetedModeFiltersCrossTenantAndNotSuccess() {
        KbDocument owned = doc("d1", DocumentStatus.SUCCESS, GraphStatus.PENDING);
        KbDocument crossTenant = doc("d2", DocumentStatus.SUCCESS, GraphStatus.PENDING);
        crossTenant.setTenantId("other-tenant");
        KbDocument parsing = doc("d3", DocumentStatus.PARSING, GraphStatus.PENDING);
        when(documentRepository.findById("d1")).thenReturn(Optional.of(owned));
        when(documentRepository.findById("d2")).thenReturn(Optional.of(crossTenant));
        when(documentRepository.findById("d3")).thenReturn(Optional.of(parsing));
        when(extractionService.extract(anyString(), anyString())).thenReturn(true);

        service.start(TENANT, List.of("d1", "d2", "d3"));

        verify(extractionService).extract(TENANT, "d1");
        verify(extractionService, never()).extract(TENANT, "d2");   // 跨租户
        verify(extractionService, never()).extract(TENANT, "d3");   // 未成功入库
        verify(store).tryStart(TENANT, 1);
    }

    @Test
    void emptyTargetsReturnsZeroViewWithoutTask() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
            eq(TENANT), anyList())).thenReturn(List.of());

        GraphBackfillView view = service.start(TENANT, null);

        assertThat(view.total()).isZero();
        assertThat(view.status()).isEqualTo(GraphBackfillView.STATUS_COMPLETED);
        verify(store, never()).tryStart(anyString(), anyInt());
    }

    @Test
    void extractionOutcomeRecordedPerDocument() {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
            eq(TENANT), anyList())).thenReturn(List.of(
            doc("d-ok", DocumentStatus.SUCCESS, GraphStatus.PENDING),
            doc("d-bad", DocumentStatus.SUCCESS, GraphStatus.PENDING)));
        when(extractionService.extract(eq(TENANT), eq("d-ok"))).thenReturn(true);
        when(extractionService.extract(eq(TENANT), eq("d-bad"))).thenReturn(false);

        service.start(TENANT, null);

        verify(store).recordResult(TENANT, true);
        verify(store).recordResult(TENANT, false);
        verify(store).finish(TENANT);
    }

    /**
     * 坑位㊳ 回归守卫：慢抽取跨越轮询窗口——旧形态 awaitAny 超时误置中断标志，
     * 任务线程中断污染后一切可中断调用（计数回写）抛 InterruptedException 且
     * 轮询热自旋；守卫断言 ① 全目标照常收敛 ② 计数回写线程零中断标志污染。
     */
    @Test
    void slowExtractionSpanningPollWindowsConvergesWithoutInterruptPollution() throws Exception {
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(
            eq(TENANT), anyList())).thenReturn(List.of(
            doc("d1", DocumentStatus.SUCCESS, GraphStatus.PENDING),
            doc("d2", DocumentStatus.SUCCESS, GraphStatus.PENDING)));
        when(extractionService.extract(anyString(), anyString())).thenAnswer(inv -> {
            Thread.sleep(1_500);   // 大于 1s 轮询窗口 → 至少经历一次超时续轮询
            return true;
        });
        AtomicBoolean interruptedSeen = new AtomicBoolean();
        doAnswer(inv -> {
            if (Thread.currentThread().isInterrupted()) {
                interruptedSeen.set(true);
            }
            return null;
        }).when(store).recordResult(anyString(), anyBoolean());
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(inv -> {
            finished.countDown();
            return null;
        }).when(store).finish(anyString());

        ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            GraphBackfillService asyncService = new GraphBackfillService(
                documentRepository, provider, asyncExecutor, store);
            ReflectionTestUtils.setField(asyncService, "concurrency", 1);
            ReflectionTestUtils.setField(asyncService, "docTimeoutMinutes", 1L);
            ReflectionTestUtils.setField(asyncService, "pollSeconds", 1L);

            asyncService.start(TENANT, null);

            assertThat(finished.await(30, TimeUnit.SECONDS)).as("回填任务收敛终态").isTrue();
            verify(extractionService).extract(TENANT, "d1");
            verify(extractionService).extract(TENANT, "d2");
            verify(store, times(2)).recordResult(eq(TENANT), eq(true));
            assertThat(interruptedSeen.get())
                .as("轮询线程不得被中断标志污染（超时 ≠ 中断，坑位㊳）").isFalse();
        } finally {
            asyncExecutor.close();
        }
    }
}
