package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.ReingestRequest;
import com.enterprise.kb.admin.dto.ReingestResult;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BadCaseService 单测（Phase 4 簇④ 4.7）——根因标注守卫、Golden 回灌文件通道
 * （upsert/联动 resolved/目录守卫）、反馈处理态租户校验。
 */
class BadCaseServiceTest {

    private KbAuditLogRepository auditLogRepository;
    private KbFeedbackRepository feedbackRepository;
    private KbMessageRepository messageRepository;
    private KbSessionRepository sessionRepository;
    private AiBusinessMetrics metrics;

    @TempDir
    Path goldenDir;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(KbAuditLogRepository.class);
        feedbackRepository = mock(KbFeedbackRepository.class);
        messageRepository = mock(KbMessageRepository.class);
        sessionRepository = mock(KbSessionRepository.class);
        metrics = new AiBusinessMetrics(new SimpleMeterRegistry());
    }

    private BadCaseService service(Path dir) {
        return new BadCaseService(auditLogRepository, feedbackRepository, messageRepository,
            sessionRepository, metrics, new JsonMapper(), dir.toString());
    }

    private static KbAuditLog audit(long id, String tenantId) {
        KbAuditLog audit = new KbAuditLog();
        audit.setId(id);
        audit.setTenantId(tenantId);
        audit.setQueryText("增值税发票认证期限是多少天？");
        return audit;
    }

    // ── 根因标注 ──

    @Test
    void annotateWritesRootCause() {
        KbAuditLog audit = audit(5L, "t-1");
        when(auditLogRepository.findById(5L)).thenReturn(Optional.of(audit));

        String applied = service(goldenDir).annotate("t-1", 5L, "retrieval_miss");

        assertThat(applied).isEqualTo("RETRIEVAL_MISS");
        assertThat(audit.getRootCause()).isEqualTo("RETRIEVAL_MISS");
        verify(auditLogRepository).save(audit);
    }

    @Test
    void annotateRejectsCrossTenantAndMissing() {
        when(auditLogRepository.findById(5L)).thenReturn(Optional.of(audit(5L, "t-other")));
        assertThatThrownBy(() -> service(goldenDir).annotate("t-1", 5L, "HALLUCINATION"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("AUDIT_LOG_NOT_FOUND");

        when(auditLogRepository.findById(6L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(goldenDir).annotate("t-1", 6L, "HALLUCINATION"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("AUDIT_LOG_NOT_FOUND");
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void annotateRejectsIllegalRootCause() {
        when(auditLogRepository.findById(5L)).thenReturn(Optional.of(audit(5L, "t-1")));

        assertThatThrownBy(() -> service(goldenDir).annotate("t-1", 5L, "OTHER"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_ROOT_CAUSE");
        verify(auditLogRepository, never()).save(any());
    }

    // ── Golden 回灌 ──

    @Test
    void reingestWritesGoldenEntryAndResolvesFeedback() throws Exception {
        KbAuditLog audit = audit(9L, "t-1");
        when(auditLogRepository.findById(9L)).thenReturn(Optional.of(audit));
        KbFeedback feedback = new KbFeedback();
        feedback.setId("f-1");
        feedback.setAuditLogId(9L);
        feedback.setResolved(false);
        when(feedbackRepository.findFirstByAuditLogId(9L)).thenReturn(Optional.of(feedback));

        ReingestResult result = service(goldenDir).reingest("t-1", new ReingestRequest(
            9L, "factoid", List.of("chunk-a"), List.of("税法汇编.pdf"), "90 天", null));

        assertThat(result.goldenId()).isEqualTo("bc-9");
        assertThat(result.category()).isEqualTo("FACTOID");
        assertThat(result.resolvedFeedbackId()).isEqualTo("f-1");
        assertThat(feedback.getResolved()).isTrue();

        Path file = goldenDir.resolve(BadCaseService.GOLDEN_FILE_NAME);
        String content = Files.readString(file);
        assertThat(content)
            .contains("\"id\" : \"bc-9\"")
            .contains("\"category\" : \"FACTOID\"")
            .contains("增值税发票认证期限是多少天？")
            .contains("\"chunk-a\"")
            .contains("税法汇编.pdf")
            .contains("90 天");
    }

    @Test
    void reingestUpsertsExistingGoldenId() throws Exception {
        KbAuditLog audit = audit(9L, "t-1");
        when(auditLogRepository.findById(9L)).thenReturn(Optional.of(audit));
        when(feedbackRepository.findFirstByAuditLogId(9L)).thenReturn(Optional.empty());
        BadCaseService svc = service(goldenDir);

        svc.reingest("t-1", new ReingestRequest(9L, null, null, null, "旧答案", null));
        svc.reingest("t-1", new ReingestRequest(9L, "REASONING", null, null, "新答案", null));

        String content = Files.readString(goldenDir.resolve(BadCaseService.GOLDEN_FILE_NAME));
        assertThat(content).doesNotContain("旧答案");
        assertThat(content).contains("新答案").contains("\"category\" : \"REASONING\"");
        assertThat(countOccurrences(content, "\"id\" : \"bc-9\"")).isEqualTo(1);
    }

    @Test
    void reingestRejectsIllegalCategoryAndCrossTenant() {
        when(auditLogRepository.findById(9L)).thenReturn(Optional.of(audit(9L, "t-1")));
        assertThatThrownBy(() -> service(goldenDir).reingest("t-1",
            new ReingestRequest(9L, "INJECTION", null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GOLDEN_ENTRY_INVALID");

        assertThatThrownBy(() -> service(goldenDir).reingest("t-other",
            new ReingestRequest(9L, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("AUDIT_LOG_NOT_FOUND");
    }

    @Test
    void reingestRejectsMissingGoldenDir() {
        when(auditLogRepository.findById(9L)).thenReturn(Optional.of(audit(9L, "t-1")));
        Path missing = goldenDir.resolve("not-exists");

        assertThatThrownBy(() -> service(missing).reingest("t-1",
            new ReingestRequest(9L, null, null, null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GOLDEN_DIR_UNAVAILABLE");
    }

    // ── 反馈处理态 ──

    @Test
    void resolveFeedbackChecksTenantOwnership() {
        KbFeedback feedback = new KbFeedback();
        feedback.setId("f-1");
        feedback.setMessageId("m-1");
        when(feedbackRepository.findById("f-1")).thenReturn(Optional.of(feedback));
        KbMessage message = new KbMessage();
        message.setId("m-1");
        message.setSessionId("s-1");
        when(messageRepository.findById("m-1")).thenReturn(Optional.of(message));
        KbSession session = new KbSession();
        session.setId("s-1");
        session.setTenantId("t-other");
        when(sessionRepository.findById("s-1")).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service(goldenDir).resolveFeedback("t-1", "f-1", true))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("FEEDBACK_NOT_FOUND");
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void resolveFeedbackUpdatesOwnedFeedback() {
        KbFeedback feedback = new KbFeedback();
        feedback.setId("f-1");
        feedback.setMessageId("m-1");
        when(feedbackRepository.findById("f-1")).thenReturn(Optional.of(feedback));
        KbMessage message = new KbMessage();
        message.setId("m-1");
        message.setSessionId("s-1");
        when(messageRepository.findById("m-1")).thenReturn(Optional.of(message));
        KbSession session = new KbSession();
        session.setId("s-1");
        session.setTenantId("t-1");
        when(sessionRepository.findById("s-1")).thenReturn(Optional.of(session));

        boolean resolved = service(goldenDir).resolveFeedback("t-1", "f-1", true);

        assertThat(resolved).isTrue();
        assertThat(feedback.getResolved()).isTrue();
        verify(feedbackRepository).save(feedback);
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
