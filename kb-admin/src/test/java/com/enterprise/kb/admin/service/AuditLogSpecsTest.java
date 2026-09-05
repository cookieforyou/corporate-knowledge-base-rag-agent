package com.enterprise.kb.admin.service;

import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.spec.AuditLogSpecs;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AuditLogSpecs 谓词接线测试（v2.35 Specification 形态修正）——Criteria 三件套
 * 深桩驱动，钉死「过滤项 null 不拼谓词 / 非空逐项拼接 / annotated 三态」映射，
 * 防止可选过滤接线漂移（真实 PG 语义归 kb-eval AdminQueryIT 回归）。
 */
class AuditLogSpecsTest {

    private CriteriaBuilder cb;
    private CriteriaQuery<?> query;
    private Root<KbAuditLog> root;

    @BeforeEach
    void setUp() {
        cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
        root = mock(Root.class, RETURNS_DEEP_STUBS);
    }

    private void apply(Specification<KbAuditLog> spec) {
        spec.toPredicate(root, query, cb);
    }

    @Test
    void nullFiltersProduceTenantPredicateOnly() {
        apply(AuditLogSpecs.search("t-1", null, null, null, null, null, null, null, null, null));

        verify(cb).equal(root.get("tenantId"), "t-1");
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(LocalDateTime.class));
        verify(cb, never()).lessThanOrEqualTo(any(Expression.class), any(LocalDateTime.class));
        verify(cb, never()).isNull(any());
        verify(cb, never()).isNotNull(any());
        verify(cb).and(any(jakarta.persistence.criteria.Predicate[].class));
        verify(query).orderBy(any(Order.class));
    }

    @Test
    void allFiltersPresentProduceEachPredicate() {
        LocalDateTime from = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 8, 15, 23, 59, 59);

        apply(AuditLogSpecs.search("t-1", from, to, "u-9", "s-9", "agent",
            "NEGATIVE", "REJECTED", "RETRIEVAL_MISS", false));

        verify(cb).equal(root.get("tenantId"), "t-1");
        verify(cb).greaterThanOrEqualTo(root.get("createdAt"), from);
        verify(cb).lessThanOrEqualTo(root.get("createdAt"), to);
        verify(cb).equal(root.get("userId"), "u-9");
        verify(cb).equal(root.get("sessionId"), "s-9");
        verify(cb).equal(root.get("mode"), "agent");
        verify(cb).equal(root.get("feedback"), "NEGATIVE");
        verify(cb).equal(root.get("status"), "REJECTED");
        verify(cb).equal(root.get("rootCause"), "RETRIEVAL_MISS");
        verify(cb).isNull(root.get("rootCause"));       // annotated=false → root_cause 为空
        verify(cb, never()).isNotNull(any());
    }

    @Test
    void annotatedTrueProducesIsNotNullPredicate() {
        apply(AuditLogSpecs.search("t-1", null, null, null, null, null, null, null, null, true));

        verify(cb).isNotNull(root.get("rootCause"));
        verify(cb, never()).isNull(any());
    }

    /** 簇⑤ E2E 审计核对项：链路过滤谓词（mode 位独立在场/缺位） */
    @Test
    void modeFilterProducesPredicateOnlyWhenPresent() {
        apply(AuditLogSpecs.search("t-1", null, null, null, null, "agent", null, null, null, null));
        verify(cb).equal(root.get("mode"), "agent");
    }
}
