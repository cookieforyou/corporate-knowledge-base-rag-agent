package com.enterprise.kb.eval.it;

import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import com.enterprise.kb.domain.spec.AuditLogSpecs;
import com.enterprise.kb.domain.spec.FeedbackSpecs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维查询真 PG 回归（v2.35 簇④ 4.7 缺陷修复）——Specification 动态谓词的
 * 过滤语义 + 租户收敛 + <b>服务端预编译提升安全</b>。
 *
 * <p><b>缺陷机理（修复前）</b>：{@code @Query} 可选参数 {@code (:p IS NULL OR ...)}
 * 在 PgJDBC 无名语句阶段不报错（绑定期携带类型），同一 SQL 文本执行达
 * {@code prepareThreshold}（默认 5 次）后提升为服务端命名预编译，Parse 期纯从
 * SQL 文本推断参数类型，{@code $n IS NULL} 出现位无类型上下文即抛
 * {@code could not determine data type of parameter}——运维中心多面板并发查询
 * 轻易越过阈值致 500。因此本 IT 各查询形态<b>重复执行 ≥6 次</b>钉死提升路径。
 */
class AdminQueryIT extends AbstractAdvisorChainIT {

    private static final String TENANT_A = "T-AQ-A";
    private static final String TENANT_B = "T-AQ-B";

    @Autowired private KbAuditLogRepository auditLogRepository;
    @Autowired private KbFeedbackRepository feedbackRepository;
    @Autowired private KbSessionRepository sessionRepository;
    @Autowired private KbMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        // 共享 PG 单例，FK 序清理四表（AdminQueryIT 字母序最先执行，不毁后续 IT 种子数据）
        feedbackRepository.deleteAllInBatch();
        messageRepository.deleteAllInBatch();
        sessionRepository.deleteAllInBatch();
        auditLogRepository.deleteAllInBatch();

        audit(TENANT_A, "u-1", "s-1", "SUCCESS", "NEGATIVE", "RETRIEVAL_MISS",
            LocalDateTime.of(2026, 8, 10, 10, 0));
        audit(TENANT_A, "u-2", null, "REJECTED", null, null,
            LocalDateTime.of(2026, 8, 12, 10, 0));
        audit(TENANT_A, "u-1", null, "ERROR", "POSITIVE", null,
            LocalDateTime.of(2026, 8, 14, 10, 0));
        audit(TENANT_B, "u-9", null, "SUCCESS", null, null,
            LocalDateTime.of(2026, 8, 14, 12, 0));

        session("s-a1", TENANT_A, "u-1");
        session("s-b1", TENANT_B, "u-9");
        message("m-a1", "s-a1");
        message("m-a2", "s-a1");
        message("m-b1", "s-b1");
        feedback("fb-a1", "m-a1", FeedbackRating.NEGATIVE, false,
            LocalDateTime.of(2026, 8, 14, 9, 0));
        feedback("fb-a2", "m-a2", FeedbackRating.POSITIVE, true,
            LocalDateTime.of(2026, 8, 14, 11, 0));
        feedback("fb-b1", "m-b1", FeedbackRating.NEGATIVE, false,
            LocalDateTime.of(2026, 8, 14, 13, 0));
    }

    @Test
    void auditSearch_filtersTenantScopeAndOrdering() {
        // 无过滤：仅本租户，created_at 倒序
        var all = auditLogRepository.findAll(
            AuditLogSpecs.search(TENANT_A, null, null, null, null, null, null, null, null),
            PageRequest.of(0, 20));
        assertThat(all.getTotalElements()).isEqualTo(3);
        assertThat(all.getContent().get(0).getStatus()).isEqualTo("ERROR");
        assertThat(all.getContent().get(2).getFeedback()).isEqualTo("NEGATIVE");

        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
            "NEGATIVE", null, null, null))).isEqualTo(1);
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
            null, "REJECTED", null, null))).isEqualTo(1);
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
            null, null, "RETRIEVAL_MISS", null))).isEqualTo(1);
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
            null, null, null, true))).isEqualTo(1);   // 已标注
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
            null, null, null, false))).isEqualTo(2);  // 未标注
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, "u-1", null,
            null, null, null, null))).isEqualTo(2);
        // 时间窗闭区间
        assertThat(countAudit(AuditLogSpecs.search(TENANT_A,
            LocalDateTime.of(2026, 8, 11, 0, 0), LocalDateTime.of(2026, 8, 13, 0, 0),
            null, null, null, null, null, null)))
            .isEqualTo(1);
        // 分页 total
        var page = auditLogRepository.findAll(
            AuditLogSpecs.search(TENANT_A, null, null, null, null, null, null, null, null),
            PageRequest.of(0, 2));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void auditSearch_repeatedExecution_survivesPreparedStatementPromotion() {
        // 各形态 ≥6 次执行：覆盖 PgJDBC prepareThreshold（5）后的命名预编译提升路径
        for (int i = 0; i < 6; i++) {
            assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
                null, null, null, null))).as("无过滤 第%d次", i + 1).isEqualTo(3);
            assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
                "NEGATIVE", null, null, null))).as("feedback 第%d次", i + 1).isEqualTo(1);
            assertThat(countAudit(AuditLogSpecs.search(TENANT_A, null, null, null, null,
                null, null, null, true))).as("annotated 第%d次", i + 1).isEqualTo(1);
            assertThat(countAudit(AuditLogSpecs.search(TENANT_A,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 0, 0),
                null, null, null, null, null, null)))
                .as("时间窗 第%d次", i + 1).isEqualTo(3);
        }
    }

    @Test
    void feedbackSearch_tenantChainAndFilters() {
        assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, null, null))).isEqualTo(2);
        assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_B, null, null))).isEqualTo(1);
        assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, FeedbackRating.NEGATIVE, null)))
            .isEqualTo(1);
        assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, null, false))).isEqualTo(1);
        assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, null, true))).isEqualTo(1);
    }

    @Test
    void feedbackSearch_repeatedExecution_survivesPreparedStatementPromotion() {
        for (int i = 0; i < 6; i++) {
            assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, null, null)))
                .as("租户链 第%d次", i + 1).isEqualTo(2);
            assertThat(countFeedback(FeedbackSpecs.tenantFeedback(TENANT_A, FeedbackRating.NEGATIVE, false)))
                .as("双过滤 第%d次", i + 1).isEqualTo(1);
        }
    }

    // ── helpers ──

    private long countAudit(org.springframework.data.jpa.domain.Specification<KbAuditLog> spec) {
        return auditLogRepository.count(spec);
    }

    private long countFeedback(org.springframework.data.jpa.domain.Specification<KbFeedback> spec) {
        return feedbackRepository.count(spec);
    }

    private void audit(String tenantId, String userId, String sessionId,
                       String status, String feedback, String rootCause, LocalDateTime createdAt) {
        KbAuditLog row = new KbAuditLog();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setQueryText("问题-" + UUID.randomUUID());
        row.setStatus(status);
        row.setFeedback(feedback);
        row.setRootCause(rootCause);
        row.setCreatedAt(createdAt);
        auditLogRepository.save(row);
    }

    private void session(String id, String tenantId, String userId) {
        KbSession session = new KbSession();
        session.setId(id);
        session.setTenantId(tenantId);
        session.setUserId(userId);
        sessionRepository.save(session);
    }

    private void message(String id, String sessionId) {
        KbMessage message = new KbMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setRole("USER");
        message.setContent("消息-" + id);
        message.setCreatedAt(LocalDateTime.of(2026, 8, 14, 8, 0));
        messageRepository.save(message);
    }

    private void feedback(String id, String messageId, FeedbackRating rating,
                          boolean resolved, LocalDateTime createdAt) {
        KbFeedback row = new KbFeedback();
        row.setId(id);
        row.setMessageId(messageId);
        row.setUserId("u-1");
        row.setRating(rating);
        row.setResolved(resolved);
        row.setCreatedAt(createdAt);
        feedbackRepository.save(row);
    }
}
