package com.enterprise.kb.api.service;

import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.spec.FeedbackSpecs;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FeedbackSpecs 谓词接线测试（v2.35 Specification 形态修正）——钉死租户链
 * 双子查询（message→session）+ rating/resolved 可选谓词映射；rating 为枚举
 * 直比（原 CAST 字符串形态随 @Query 废除）。真实 PG 语义归 kb-eval AdminQueryIT。
 *
 * <p>深桩引用一律先取后 verify——verify 实参位触发 mock 调用会引发
 * Mockito UnfinishedVerification（实证）。
 */
class FeedbackSpecsTest {

    private CriteriaBuilder cb;
    private CriteriaQuery<?> query;
    private Root<KbFeedback> root;
    private Subquery<String> sq;
    private Root<KbSession> sessionRoot;
    private Root<KbMessage> messageRoot;
    private Path<Object> tenantPath;
    private Path<Object> ratingPath;
    private Path<Object> resolvedPath;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
        query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
        root = mock(Root.class, RETURNS_DEEP_STUBS);
        sq = mock(Subquery.class, RETURNS_DEEP_STUBS);
        sessionRoot = mock(Root.class, RETURNS_DEEP_STUBS);
        messageRoot = mock(Root.class, RETURNS_DEEP_STUBS);
        // 显式桩固定子查询/根引用（spec 内两次 subquery 同 mock 于接线断言无碍）
        when(query.subquery(String.class)).thenReturn(sq);
        when(sq.from(KbSession.class)).thenReturn(sessionRoot);
        when(sq.from(KbMessage.class)).thenReturn(messageRoot);
        // 预取路径引用（深桩同参缓存保证与 spec 内部调用同一实例）
        tenantPath = sessionRoot.get("tenantId");
        ratingPath = root.get("rating");
        resolvedPath = root.get("resolved");
    }

    private void apply(Specification<KbFeedback> spec) {
        spec.toPredicate(root, query, cb);
    }

    @Test
    void buildsTenantChainSubqueriesAndSkipsNullFilters() {
        apply(FeedbackSpecs.tenantFeedback("t-1", null, null));

        verify(query, times(2)).subquery(String.class);   // message→session 两级
        verify(sq).from(KbSession.class);
        verify(sq).from(KbMessage.class);
        verify(cb).equal(tenantPath, "t-1");
        verify(cb, never()).equal(eq(ratingPath), any(Object.class));
        verify(cb, never()).equal(eq(resolvedPath), any(Object.class));
        verify(cb).and(any(jakarta.persistence.criteria.Predicate[].class));
    }

    @Test
    void ratingAndResolvedProduceTypedPredicates() {
        apply(FeedbackSpecs.tenantFeedback("t-1", FeedbackRating.NEGATIVE, false));

        verify(cb).equal(ratingPath, FeedbackRating.NEGATIVE);
        verify(cb).equal(resolvedPath, false);
    }
}
