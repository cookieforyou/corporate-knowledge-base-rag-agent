package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.AuditLogPage;
import com.enterprise.kb.admin.dto.AuditLogView;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogQueryService 单测（Phase 4 簇④ 4.7）——过滤参数透传、分页口径、
 * 期望回答联查与非法过滤值守卫。
 */
class AuditLogQueryServiceTest {

    private KbAuditLogRepository auditLogRepository;
    private KbFeedbackRepository feedbackRepository;
    private AuditLogQueryService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(KbAuditLogRepository.class);
        feedbackRepository = mock(KbFeedbackRepository.class);
        service = new AuditLogQueryService(auditLogRepository, feedbackRepository);
    }

    private static KbAuditLog audit(long id, String tenantId) {
        KbAuditLog audit = new KbAuditLog();
        audit.setId(id);
        audit.setTenantId(tenantId);
        audit.setQueryText("问题 " + id);
        audit.setStatus("SUCCESS");
        audit.setCreatedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        return audit;
    }

    private void stubSearch(List<KbAuditLog> rows, long total) {
        when(auditLogRepository.search(eq("t-1"), any(), any(), any(), any(),
            any(), any(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 20), total));
    }

    @Test
    void searchAssemblesViewWithFeedbackExpectedAnswer() {
        KbAuditLog row = audit(7L, "t-1");
        stubSearch(List.of(row), 1);
        KbFeedback feedback = new KbFeedback();
        feedback.setAuditLogId(7L);
        feedback.setExpectedAnswer("期望回答");
        when(feedbackRepository.findByAuditLogIdIn(List.of(7L))).thenReturn(List.of(feedback));

        AuditLogPage page = service.search("t-1", null, null, null, null, null, null, null, null, null, null);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
        AuditLogView view = page.items().get(0);
        assertThat(view.id()).isEqualTo(7L);
        assertThat(view.queryText()).isEqualTo("问题 7");
        assertThat(view.feedbackExpectedAnswer()).isEqualTo("期望回答");
        verify(auditLogRepository).search(eq("t-1"), isNull(), isNull(), isNull(), isNull(),
            isNull(), isNull(), isNull(), isNull(), eq(PageRequest.of(0, 20)));
    }

    @Test
    void searchPassesFiltersAndPaginates() {
        stubSearch(List.of(), 0);

        service.search("t-1", "2026-08-01T00:00:00", "2026-08-15T23:59:59",
            "u-9", "s-9", "negative", "rejected", "retrieval_miss", false, 2, 50);

        verify(auditLogRepository).search(eq("t-1"),
            eq(LocalDateTime.of(2026, 8, 1, 0, 0)), eq(LocalDateTime.of(2026, 8, 15, 23, 59, 59)),
            eq("u-9"), eq("s-9"), eq("NEGATIVE"), eq("REJECTED"), eq("RETRIEVAL_MISS"),
            eq(false), eq(PageRequest.of(2, 50)));
    }

    @Test
    void searchCapsSizeAt100AndDefaultsPage() {
        stubSearch(List.of(), 0);

        AuditLogPage page = service.search("t-1", null, null, null, null, null, null, null, null, -3, 500);

        assertThat(page.size()).isEqualTo(100);
        assertThat(page.page()).isZero();
        verify(auditLogRepository).search(eq("t-1"), any(), any(), any(), any(),
            any(), any(), any(), any(), eq(PageRequest.of(0, 100)));
    }

    @Test
    void searchRejectsMalformedTime() {
        assertThatThrownBy(() -> service.search("t-1", "2026/08/01", null, null, null,
            null, null, null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_TIME_FORMAT");
    }

    @Test
    void searchRejectsIllegalFilterValues() {
        assertThatThrownBy(() -> service.search("t-1", null, null, null, null,
            "MAYBE", null, null, null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_FILTER");
        assertThatThrownBy(() -> service.search("t-1", null, null, null, null,
            null, null, "UNKNOWN_CAUSE", null, null, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_FILTER");
    }
}
