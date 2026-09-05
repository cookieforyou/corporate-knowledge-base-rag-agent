package com.enterprise.kb.api.service;

import com.enterprise.kb.api.dto.DocumentProcessingView;
import com.enterprise.kb.api.dto.DocumentProcessingView.ProcessingDocument;
import com.enterprise.kb.api.dto.StatsOverview;
import com.enterprise.kb.api.dto.StatsOverview.DailyIngestion;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.domain.spec.AuditLogSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库统计服务（Phase 4 簇② 任务 4.6：运维仪表盘数据接口）
 *
 * <p>只读聚合，全部按租户隔离。聚合源以 kb_document 单表为主；chunk 总量
 * （chunkTotal）自簇③ 4.4 软删门面生效起切换为 kb_chunk 精确口径——JOIN
 * 文档租户维度排除软删 chunk（簇② 4.6 曾取文档侧 chunk_count 近似）。
 * 入库趋势的 chunk 曲线仍取文档侧口径（按日聚合不直查 kb_chunk 大表）。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {

    /** 入库趋势窗口（天）：仪表盘近两周曲线 */
    static final int TREND_DAYS = 14;

    /** 未解析文档（parse_route 为 null）在路由分布中的归组键 */
    static final String ROUTE_UNKNOWN = "UNKNOWN";

    /** Bad Case 计数口径：点踩反馈（与 BadCaseAdminController 运营查询同值域） */
    private static final String FEEDBACK_NEGATIVE = "NEGATIVE";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final KbAuditLogRepository auditLogRepository;

    /** 知识库统计总览：文档/状态分布/chunk 规模/路由分布/入库趋势 + Bad Case 双计数 */
    public StatsOverview overview(String tenantId) {
        Map<String, Long> byStatus = zeroFilledStatusCounts();
        for (Object[] row : documentRepository.countGroupByStatus(tenantId)) {
            byStatus.put(((DocumentStatus) row[0]).name(), (Long) row[1]);
        }

        Map<String, Long> byRoute = new LinkedHashMap<>();
        for (Object[] row : documentRepository.countGroupByParseRoute(tenantId)) {
            String route = row[0] == null ? ROUTE_UNKNOWN : row[0].toString();
            byRoute.merge(route, (Long) row[1], Long::sum);
        }

        // Bad Case 双计数（12 §12.12 二轮）：仪表盘计数聚合进 stats 域——前端不再
        // 直调 /api/v1/admin/audit-logs（admin 域限权后非超管 403；聚合数字全员可读，
        // 与审计明细查询分域）。谓词复用 AuditLogSpecs 运营查询同源口径
        long badCaseTotal = auditLogRepository.count(AuditLogSpecs.search(
            tenantId, null, null, null, null, null, FEEDBACK_NEGATIVE, null, null, null));
        long unannotatedTotal = auditLogRepository.count(AuditLogSpecs.search(
            tenantId, null, null, null, null, null, FEEDBACK_NEGATIVE, null, null, Boolean.FALSE));

        return new StatsOverview(
            documentRepository.countByTenantId(tenantId),
            byStatus,
            chunkRepository.countAliveByTenantId(tenantId),
            byRoute,
            dailyIngestion(tenantId),
            badCaseTotal,
            unannotatedTotal);
    }

    /** 文档解析状态视图：处理中三态计数 + 处理中文档清单 */
    public DocumentProcessingView processing(String tenantId) {
        List<DocumentStatus> processingStatuses = List.of(
            DocumentStatus.UPLOADING, DocumentStatus.PARSING, DocumentStatus.REINDEXING);
        List<ProcessingDocument> documents = documentRepository
            .findByTenantIdAndStatusInOrderByUpdatedAtDesc(tenantId, processingStatuses)
            .stream()
            .map(doc -> new ProcessingDocument(doc.getId(), doc.getName(),
                doc.getStatus().name(), doc.getParseRoute(), doc.getUpdatedAt()))
            .toList();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (DocumentStatus status : processingStatuses) {
            counts.put(status.name(), 0L);
        }
        documents.forEach(doc -> counts.merge(doc.status(), 1L, Long::sum));
        return new DocumentProcessingView(counts, documents);
    }

    /** 近 14 天入库趋势：聚合行映射后按日补 0，保证前端连续绘图 */
    private List<DailyIngestion> dailyIngestion(String tenantId) {
        LocalDate today = LocalDate.now();
        Map<LocalDate, long[]> aggregated = new LinkedHashMap<>();
        for (Object[] row : documentRepository.dailyIngestion(
                tenantId, today.minusDays(TREND_DAYS - 1L).atStartOfDay())) {
            aggregated.put((LocalDate) row[0], new long[]{(Long) row[1], (Long) row[2]});
        }
        List<DailyIngestion> trend = new ArrayList<>(TREND_DAYS);
        for (int offset = TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            long[] value = aggregated.getOrDefault(date, new long[]{0, 0});
            trend.add(new DailyIngestion(DATE_FORMAT.format(date), value[0], value[1]));
        }
        return trend;
    }

    /** DocumentStatus 五值全量补 0——前端面板不因缺键漏渲染 */
    private static Map<String, Long> zeroFilledStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DocumentStatus status : DocumentStatus.values()) {
            counts.put(status.name(), 0L);
        }
        return counts;
    }
}
