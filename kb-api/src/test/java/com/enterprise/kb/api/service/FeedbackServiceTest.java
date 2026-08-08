package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.api.dto.FeedbackItem;
import com.enterprise.kb.api.dto.FeedbackRequest;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.FeedbackRating;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户反馈服务测试（3.17）——落库/upsert/租户隔离/审计回填/指标/Bad Case 查询
 *
 * <p>messageWaitMillis=0：存在性检查退化为单次（规避轮询等待的测试时延）。
 */
class FeedbackServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-1";

    private KbFeedbackRepository feedbackRepository;
    private KbMessageRepository messageRepository;
    private KbSessionRepository sessionRepository;
    private KbAuditLogRepository auditLogRepository;
    private SimpleMeterRegistry meterRegistry;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(KbFeedbackRepository.class);
        messageRepository = mock(KbMessageRepository.class);
        sessionRepository = mock(KbSessionRepository.class);
        auditLogRepository = mock(KbAuditLogRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new FeedbackService(feedbackRepository, messageRepository, sessionRepository,
            auditLogRepository, new AiBusinessMetrics(meterRegistry),
            JsonMapper.builder().build(), 0);
    }

    /** 消息/会话归属就位：messageId → 本租户本用户会话下的助手消息 */
    private void givenOwnedMessage(String messageId, String sessionId) {
        when(messageRepository.existsById(messageId)).thenReturn(true);
        KbMessage message = new KbMessage();
        message.setId(messageId);
        message.setSessionId(sessionId);
        message.setRole("ASSISTANT");
        message.setContent("回答内容");
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));

        KbSession session = new KbSession();
        session.setId(sessionId);
        session.setTenantId(TENANT);
        session.setUserId(USER);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    }

    private KbFeedback captureSaved() {
        ArgumentCaptor<KbFeedback> captor = ArgumentCaptor.forClass(KbFeedback.class);
        verify(feedbackRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void submitCreatesFeedbackWithAuditBackfillAndLikeMetric() {
        givenOwnedMessage("m-1", "s-1");
        when(feedbackRepository.findByMessageIdAndUserId("m-1", USER)).thenReturn(Optional.empty());
        KbAuditLog audit = new KbAuditLog();
        audit.setId(42L);
        when(auditLogRepository.findFirstByTraceId("trace-1")).thenReturn(Optional.of(audit));
        when(feedbackRepository.save(any(KbFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        KbFeedback saved = service.submit(TENANT, USER, new FeedbackRequest(
            "m-1", "trace-1", "POSITIVE", null, List.of("准确")));

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getRating()).isEqualTo(FeedbackRating.POSITIVE);
        assertThat(saved.getAuditLogId()).isEqualTo(42L);
        assertThat(saved.getFeedbackTags()).contains("准确");
        assertThat(meterRegistry.counter("rag.feedback.like").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.feedback.dislike").count()).isZero();
        // 审计行回填 feedback 列（§11.6.3）
        assertThat(audit.getFeedback()).isEqualTo("POSITIVE");
        verify(auditLogRepository).save(audit);
    }

    @Test
    void submitNegativeCountsDislikeMetric() {
        givenOwnedMessage("m-2", "s-1");
        when(feedbackRepository.findByMessageIdAndUserId("m-2", USER)).thenReturn(Optional.empty());
        when(feedbackRepository.save(any(KbFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        service.submit(TENANT, USER, new FeedbackRequest(
            "m-2", null, "negative", "期望的正确回答", null));

        KbFeedback saved = captureSaved();
        assertThat(saved.getRating()).isEqualTo(FeedbackRating.NEGATIVE);
        assertThat(saved.getExpectedAnswer()).isEqualTo("期望的正确回答");
        assertThat(saved.getAuditLogId()).isNull();
        assertThat(meterRegistry.counter("rag.feedback.dislike").count()).isEqualTo(1.0);
    }

    @Test
    void resubmitUpdatesExistingFeedbackInPlace() {
        givenOwnedMessage("m-3", "s-1");
        KbFeedback existing = new KbFeedback();
        existing.setId("fb-existing");
        existing.setMessageId("m-3");
        existing.setUserId(USER);
        existing.setRating(FeedbackRating.POSITIVE);
        existing.setResolved(false);
        existing.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        when(feedbackRepository.findByMessageIdAndUserId("m-3", USER)).thenReturn(Optional.of(existing));
        when(feedbackRepository.save(any(KbFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        KbFeedback saved = service.submit(TENANT, USER, new FeedbackRequest(
            "m-3", null, "NEGATIVE", "改主意了", null));

        assertThat(saved.getId()).isEqualTo("fb-existing");
        assertThat(saved.getRating()).isEqualTo(FeedbackRating.NEGATIVE);
        verify(feedbackRepository, never()).delete(any());
    }

    @Test
    void messageNotYetArchivedRejectedAsNotFound() {
        when(messageRepository.existsById("m-ghost")).thenReturn(false);

        assertThatThrownBy(() -> service.submit(TENANT, USER, new FeedbackRequest(
            "m-ghost", null, "POSITIVE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MESSAGE_NOT_FOUND");
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void crossTenantReferenceHiddenAsNotFound() {
        givenOwnedMessage("m-4", "s-other");
        KbSession foreignSession = new KbSession();
        foreignSession.setId("s-other");
        foreignSession.setTenantId("tenant-evil");
        foreignSession.setUserId(USER);
        when(sessionRepository.findById("s-other")).thenReturn(Optional.of(foreignSession));

        assertThatThrownBy(() -> service.submit(TENANT, USER, new FeedbackRequest(
            "m-4", null, "POSITIVE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MESSAGE_NOT_FOUND");
        verify(feedbackRepository, never()).save(any());
    }

    @Test
    void crossUserReferenceHiddenAsNotFound() {
        givenOwnedMessage("m-5", "s-colleague");
        KbSession colleagueSession = new KbSession();
        colleagueSession.setId("s-colleague");
        colleagueSession.setTenantId(TENANT);
        colleagueSession.setUserId("user-other");
        when(sessionRepository.findById("s-colleague")).thenReturn(Optional.of(colleagueSession));

        assertThatThrownBy(() -> service.submit(TENANT, USER, new FeedbackRequest(
            "m-5", null, "POSITIVE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MESSAGE_NOT_FOUND");
    }

    @Test
    void invalidRatingRejected() {
        assertThatThrownBy(() -> service.submit(TENANT, USER, new FeedbackRequest(
            "m-1", null, "MAYBE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_FEEDBACK");
    }

    @Test
    void missingMessageIdRejected() {
        assertThatThrownBy(() -> service.submit(TENANT, USER, new FeedbackRequest(
            " ", null, "POSITIVE", null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("INVALID_FEEDBACK");
    }

    @Test
    void auditBackfillFailureDoesNotBlockFeedback() {
        givenOwnedMessage("m-6", "s-1");
        when(feedbackRepository.findByMessageIdAndUserId("m-6", USER)).thenReturn(Optional.empty());
        when(auditLogRepository.findFirstByTraceId(anyString())).thenThrow(new RuntimeException("PG 抖动"));
        when(feedbackRepository.save(any(KbFeedback.class))).thenAnswer(inv -> inv.getArgument(0));

        KbFeedback saved = service.submit(TENANT, USER, new FeedbackRequest(
            "m-6", "trace-x", "POSITIVE", null, null));

        assertThat(saved.getAuditLogId()).isNull();
        verify(feedbackRepository).save(any(KbFeedback.class));
    }

    @Test
    void searchAttachesConversationAndParsesTags() {
        KbFeedback row = new KbFeedback();
        row.setId("fb-1");
        row.setMessageId("m-10");
        row.setRating(FeedbackRating.NEGATIVE);
        row.setExpectedAnswer("应该是 13%");
        row.setFeedbackTags("[\"答案不准确\"]");
        row.setResolved(false);
        row.setCreatedAt(LocalDateTime.now());
        row.setAuditLogId(7L);
        when(feedbackRepository.searchTenantFeedback(eq(TENANT), eq("NEGATIVE"), eq(false), any(Pageable.class)))
            .thenReturn(List.of(row));

        KbMessage userMessage = new KbMessage();
        userMessage.setId("m-9");
        userMessage.setSessionId("s-9");
        userMessage.setRole("USER");
        userMessage.setContent("增值税税率是多少？");
        KbMessage assistantMessage = new KbMessage();
        assistantMessage.setId("m-10");
        assistantMessage.setSessionId("s-9");
        assistantMessage.setRole("ASSISTANT");
        assistantMessage.setContent("3%");
        when(messageRepository.findAllById(List.of("m-10"))).thenReturn(List.of(assistantMessage));
        when(messageRepository.findBySessionIdOrderByCreatedAt("s-9"))
            .thenReturn(List.of(userMessage, assistantMessage));

        List<FeedbackItem> items = service.search(TENANT, "negative", false, 50);

        assertThat(items).hasSize(1);
        FeedbackItem item = items.get(0);
        assertThat(item.query()).isEqualTo("增值税税率是多少？");
        assertThat(item.answer()).isEqualTo("3%");
        assertThat(item.rating()).isEqualTo("NEGATIVE");
        assertThat(item.tags()).containsExactly("答案不准确");
        assertThat(item.auditLogId()).isEqualTo(7L);
        assertThat(item.sessionId()).isEqualTo("s-9");
    }
}
