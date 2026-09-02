package com.enterprise.kb.api.service;

import com.enterprise.kb.api.dto.DocumentProcessingView;
import com.enterprise.kb.api.dto.StatsOverview;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统计服务测试（Phase 4 簇② 任务 4.6）：聚合映射 + 补 0 口径；
 * chunkTotal 自簇③ 起走 kb_chunk 存活精确口径（countAliveByTenantId）
 */
class StatsServiceTest {

    private final KbDocumentRepository documentRepository = mock(KbDocumentRepository.class);
    private final KbChunkRepository chunkRepository = mock(KbChunkRepository.class);
    private final KbAuditLogRepository auditLogRepository = mock(KbAuditLogRepository.class);
    private StatsService statsService;

    @BeforeEach
    void setUp() {
        statsService = new StatsService(documentRepository, chunkRepository, auditLogRepository);
    }

    @Test
    void overviewZeroFillsStatusAndMapsAggregates() {
        when(documentRepository.countByTenantId("t1")).thenReturn(5L);
        when(documentRepository.countGroupByStatus("t1")).thenReturn(List.of(
            new Object[]{DocumentStatus.SUCCESS, 3L},
            new Object[]{DocumentStatus.FAILED, 2L}));
        when(documentRepository.countGroupByParseRoute("t1")).thenReturn(List.of(
            new Object[]{"DEEP", 3L},
            new Object[]{null, 2L}));
        when(chunkRepository.countAliveByTenantId("t1")).thenReturn(42L);
        when(documentRepository.dailyIngestion(eq("t1"), any(LocalDateTime.class))).thenReturn(List.of());
        // Bad Case 双计数（12 §12.12 二轮聚合口径）：连续 stub 按调用序
        // = badCaseTotal（点踩全量）→ unannotatedTotal（点踩未标注）
        when(auditLogRepository.count(any(Specification.class))).thenReturn(7L, 3L);

        StatsOverview overview = statsService.overview("t1");

        assertThat(overview.documentTotal()).isEqualTo(5);
        assertThat(overview.chunkTotal()).isEqualTo(42);
        assertThat(overview.badCaseTotal()).isEqualTo(7);
        assertThat(overview.unannotatedTotal()).isEqualTo(3);
        assertThat(overview.documentsByStatus())
            .containsEntry("SUCCESS", 3L)
            .containsEntry("FAILED", 2L)
            .containsEntry("UPLOADING", 0L)
            .containsEntry("PARSING", 0L)
            .containsEntry("REINDEXING", 0L);
        assertThat(overview.documentsByParseRoute())
            .containsEntry("DEEP", 3L)
            .containsEntry("UNKNOWN", 2L);
        assertThat(overview.dailyIngestion()).hasSize(StatsService.TREND_DAYS);
        assertThat(overview.dailyIngestion()).allSatisfy(day -> {
            assertThat(day.documents()).isZero();
            assertThat(day.chunks()).isZero();
        });
    }

    @Test
    void overviewDailyIngestionMergesRowsAndZeroFillsGaps() {
        LocalDate today = LocalDate.now();
        when(documentRepository.countByTenantId(anyString())).thenReturn(0L);
        when(documentRepository.countGroupByStatus(anyString())).thenReturn(List.of());
        when(documentRepository.countGroupByParseRoute(anyString())).thenReturn(List.of());
        when(chunkRepository.countAliveByTenantId(anyString())).thenReturn(0L);
        when(documentRepository.dailyIngestion(eq("t1"), any(LocalDateTime.class))).thenReturn(List.of(
            new Object[]{today, 2L, 30L},
            new Object[]{today.minusDays(3), 1L, 7L}));

        StatsOverview overview = statsService.overview("t1");

        assertThat(overview.dailyIngestion()).hasSize(StatsService.TREND_DAYS);
        // 末日 = 当天（倒序补 0 后按时间升序输出）
        StatsOverview.DailyIngestion last = overview.dailyIngestion()
            .get(StatsService.TREND_DAYS - 1);
        assertThat(last.date()).isEqualTo(today.toString());
        assertThat(last.documents()).isEqualTo(2);
        assertThat(last.chunks()).isEqualTo(30);
        StatsOverview.DailyIngestion gapFilled = overview.dailyIngestion()
            .get(StatsService.TREND_DAYS - 2);
        assertThat(gapFilled.documents()).isZero();
    }

    @Test
    void processingCountsAndMapsDocuments() {
        KbDocument parsing = doc("d1", "手册.pdf", DocumentStatus.PARSING, "DEEP");
        KbDocument reindexing = doc("d2", "规范.docx", DocumentStatus.REINDEXING, "NATIVE");
        when(documentRepository.findByTenantIdAndStatusInOrderByUpdatedAtDesc(eq("t1"), any()))
            .thenReturn(List.of(reindexing, parsing));

        DocumentProcessingView view = statsService.processing("t1");

        assertThat(view.counts())
            .containsEntry("UPLOADING", 0L)
            .containsEntry("PARSING", 1L)
            .containsEntry("REINDEXING", 1L);
        assertThat(view.documents()).hasSize(2);
        assertThat(view.documents().get(0).id()).isEqualTo("d2");
        assertThat(view.documents().get(1).status()).isEqualTo("PARSING");
    }

    private static KbDocument doc(String id, String name, DocumentStatus status, String route) {
        KbDocument document = new KbDocument();
        document.setId(id);
        document.setName(name);
        document.setStatus(status);
        document.setParseRoute(route);
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }
}
