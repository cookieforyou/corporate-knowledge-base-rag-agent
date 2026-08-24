package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.FeedbackExportSummary;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 反馈微调数据导出单测（簇② 5.10 批4）——派生规则 / 质量过滤 /
 * JSONL 形态 / 门槛对照 / PII 消毒 / 确定性顺序
 */
class FeedbackExportServiceTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private KbFeedbackRepository feedbackRepository;
    private KbMessageRepository messageRepository;
    private KbSessionRepository sessionRepository;
    private KbAuditLogRepository auditLogRepository;
    private FeedbackExportService service;

    @BeforeEach
    void setUp() {
        feedbackRepository = mock(KbFeedbackRepository.class);
        messageRepository = mock(KbMessageRepository.class);
        sessionRepository = mock(KbSessionRepository.class);
        auditLogRepository = mock(KbAuditLogRepository.class);
        service = new FeedbackExportService(feedbackRepository, messageRepository,
            sessionRepository, auditLogRepository, PiiRecognizerRegistry.defaults(), jsonMapper);
        when(feedbackRepository.findAll(any(Specification.class))).thenReturn(List.of());
        when(messageRepository.findAllById(anyList())).thenReturn(List.of());
        when(auditLogRepository.findAllById(anyList())).thenReturn(List.of());
    }

    // ── JSONL 行形态 ──

    @Test
    void sftLineRendersChatMlSingleTurnWithoutSystem() {
        String line = FeedbackExportService.sftLine(
            new FeedbackExportService.SftPair("问题", "回答", false), jsonMapper);

        Map<String, Object> parsed = parse(line);
        assertThat(parsed).containsOnlyKeys("messages");
        List<Map<String, String>> messages = messages(parsed);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("role", "user").containsEntry("content", "问题");
        assertThat(messages.get(1)).containsEntry("role", "assistant").containsEntry("content", "回答");
    }

    @Test
    void dpoLineRendersPromptWithChosenAndRejected() {
        String line = FeedbackExportService.dpoLine(
            new FeedbackExportService.DpoPair("问题", "订正回答", "原回答"), jsonMapper);

        Map<String, Object> parsed = parse(line);
        assertThat(parsed).containsOnlyKeys("messages", "chosen", "rejected");
        List<Map<String, String>> messages = messages(parsed);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("role", "user").containsEntry("content", "问题");
        assertThat((Map<String, String>) parsed.get("chosen"))
            .containsEntry("role", "assistant").containsEntry("content", "订正回答");
        assertThat((Map<String, String>) parsed.get("rejected"))
            .containsEntry("role", "assistant").containsEntry("content", "原回答");
    }

    @Test
    void toJsonlBytesTerminatesEachLineAndHandlesEmpty() {
        byte[] body = FeedbackExportService.toJsonlBytes(List.of("a", "b"));
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("a\nb\n");
        assertThat(FeedbackExportService.toJsonlBytes(List.of())).isEmpty();
    }

    // ── 派生规则（classify 纯函数）──

    private static FeedbackExportService.ExportCandidate candidate(
        FeedbackRating rating, String query, String answer, String expected, String auditStatus) {
        return new FeedbackExportService.ExportCandidate("fb-1", rating, query, answer, expected, auditStatus);
    }

    @Test
    void classifyPositiveGoesToAdoptionChannel() {
        var buckets = FeedbackExportService.classify(
            List.of(candidate(FeedbackRating.POSITIVE, "q", "a", null, "SUCCESS")), 0);

        assertThat(buckets.sftPairs()).containsExactly(
            new FeedbackExportService.SftPair("q", "a", false));
        assertThat(buckets.dpoPairs()).isEmpty();
        assertThat(buckets.positiveFeedback()).isEqualTo(1);
    }

    @Test
    void classifyNegativeWithCorrectionFeedsBothChannels() {
        var buckets = FeedbackExportService.classify(
            List.of(candidate(FeedbackRating.NEGATIVE, "q", "a", "期望", "SUCCESS")), 0);

        assertThat(buckets.sftPairs()).containsExactly(
            new FeedbackExportService.SftPair("q", "期望", true));
        assertThat(buckets.dpoPairs()).containsExactly(
            new FeedbackExportService.DpoPair("q", "期望", "a"));
        assertThat(buckets.negativeWithExpectedAnswer()).isEqualTo(1);
    }

    @Test
    void classifyNegativeWithoutCorrectionHasNoExportMaterial() {
        var buckets = FeedbackExportService.classify(
            List.of(candidate(FeedbackRating.NEGATIVE, "q", "a", null, "SUCCESS")), 0);

        assertThat(buckets.sftPairs()).isEmpty();
        assertThat(buckets.dpoPairs()).isEmpty();
        assertThat(buckets.negativeWithoutCorrection()).isEqualTo(1);
    }

    @Test
    void classifySkipsAuditRejectedAndErrorButKeepsSuccessAndAbsent() {
        var buckets = FeedbackExportService.classify(List.of(
            candidate(FeedbackRating.POSITIVE, "q1", "a1", null, "REJECTED"),
            candidate(FeedbackRating.POSITIVE, "q2", "a2", null, "ERROR"),
            candidate(FeedbackRating.POSITIVE, "q3", "a3", null, "SUCCESS"),
            candidate(FeedbackRating.POSITIVE, "q4", "a4", null, null)), 0);

        assertThat(buckets.sftPairs()).hasSize(2);
        assertThat(buckets.auditNotClean()).isEqualTo(2);
    }

    @Test
    void classifySkipsMissingQueryAndMissingPositiveAnswer() {
        var buckets = FeedbackExportService.classify(List.of(
            candidate(FeedbackRating.POSITIVE, null, "a", null, null),
            candidate(FeedbackRating.POSITIVE, "q", null, null, null)), 0);

        assertThat(buckets.sftPairs()).isEmpty();
        assertThat(buckets.missingConversation()).isEqualTo(2);
    }

    @Test
    void classifyKeepsCorrectionSftWhenAnswerMissingButDropsDpo() {
        var buckets = FeedbackExportService.classify(
            List.of(candidate(FeedbackRating.NEGATIVE, "q", null, "期望", null)), 0);

        assertThat(buckets.sftPairs()).hasSize(1);
        assertThat(buckets.dpoPairs()).isEmpty();
        assertThat(buckets.missingConversation()).isEqualTo(1);
    }

    // ── 门槛对照 ──

    @Test
    void toSummaryReportsThresholdAttainment() {
        List<FeedbackExportService.SftPair> sftPairs = new java.util.ArrayList<>();
        List<FeedbackExportService.DpoPair> dpoPairs = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sftPairs.add(new FeedbackExportService.SftPair("q" + i, "a" + i, false));
        }
        for (int i = 0; i < 49; i++) {
            dpoPairs.add(new FeedbackExportService.DpoPair("q" + i, "c" + i, "r" + i));
        }
        var buckets = new FeedbackExportService.Buckets(List.copyOf(sftPairs), List.copyOf(dpoPairs),
            120, 80, 49, 0, 0, 31, 0);

        FeedbackExportSummary summary = FeedbackExportService.toSummary(buckets);

        assertThat(summary.sftRecords()).isEqualTo(100);
        assertThat(summary.sftTargetMet()).isTrue();      // 恰达 100 门槛
        assertThat(summary.dpoRecords()).isEqualTo(49);
        assertThat(summary.dpoTargetMet()).isFalse();      // 差 1 对未达 50
        assertThat(summary.totalFeedback()).isEqualTo(200);
    }

    // ── 采集链路（仓储 mock 端到端）──

    private static KbFeedback feedback(String id, String messageId, FeedbackRating rating,
                                       String expectedAnswer, Long auditLogId, LocalDateTime createdAt) {
        KbFeedback f = new KbFeedback();
        f.setId(id);
        f.setMessageId(messageId);
        f.setUserId("u-1");
        f.setRating(rating);
        f.setExpectedAnswer(expectedAnswer);
        f.setAuditLogId(auditLogId);
        f.setCreatedAt(createdAt);
        return f;
    }

    private static KbMessage message(String id, String sessionId, String role, String content) {
        KbMessage m = new KbMessage();
        m.setId(id);
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    /** 双反馈场景：👍（早）+ 👎附期望回答（晚），👎 关联 REJECTED 审计行 */
    private void givenTwoFeedbackScenario() {
        KbFeedback positive = feedback("fb-1", "m-a2", FeedbackRating.POSITIVE, null, null,
            LocalDateTime.of(2026, 8, 1, 10, 0));
        KbFeedback negative = feedback("fb-2", "m-b2", FeedbackRating.NEGATIVE, "正确答案", 99L,
            LocalDateTime.of(2026, 8, 2, 10, 0));
        when(feedbackRepository.findAll(any(Specification.class)))
            .thenReturn(List.of(negative, positive));   // 查询侧倒序，服务内须正序化

        KbMessage q1 = message("m-a1", "s-1", "USER", "问题甲");
        KbMessage a1 = message("m-a2", "s-1", "ASSISTANT", "回答甲");
        KbMessage q2 = message("m-b1", "s-2", "USER", "问题乙");
        KbMessage a2 = message("m-b2", "s-2", "ASSISTANT", "回答乙");
        when(messageRepository.findAllById(anyList())).thenReturn(List.of(a1, a2));
        when(messageRepository.findBySessionIdOrderByCreatedAt("s-1")).thenReturn(List.of(q1, a1));
        when(messageRepository.findBySessionIdOrderByCreatedAt("s-2")).thenReturn(List.of(q2, a2));

        KbAuditLog audit = new KbAuditLog();
        audit.setId(99L);
        audit.setStatus("SUCCESS");
        when(auditLogRepository.findAllById(anyList())).thenReturn(List.of(audit));
    }

    @Test
    void exportLinesResolveConversationAndOrderAscending() {
        givenTwoFeedbackScenario();

        List<String> sft = service.exportLines("t-1", FeedbackExportService.ExportFormat.SFT);

        assertThat(sft).hasSize(2);
        assertThat(sft.get(0)).contains("问题甲").contains("回答甲");   // createdAt 正序：👍 在前
        assertThat(sft.get(1)).contains("问题乙").contains("正确答案"); // 订正通道取期望回答

        List<String> dpo = service.exportLines("t-1", FeedbackExportService.ExportFormat.DPO);
        assertThat(dpo).hasSize(1);
        assertThat(dpo.get(0)).contains("问题乙").contains("正确答案").contains("回答乙");
    }

    @Test
    void summaryAggregatesScenarioCounts() {
        givenTwoFeedbackScenario();

        FeedbackExportSummary summary = service.summary("t-1");

        assertThat(summary.totalFeedback()).isEqualTo(2);
        assertThat(summary.positiveFeedback()).isEqualTo(1);
        assertThat(summary.negativeFeedback()).isEqualTo(1);
        assertThat(summary.negativeWithExpectedAnswer()).isEqualTo(1);
        assertThat(summary.sftRecords()).isEqualTo(2);
        assertThat(summary.sftFromPositive()).isEqualTo(1);
        assertThat(summary.sftFromCorrection()).isEqualTo(1);
        assertThat(summary.dpoRecords()).isEqualTo(1);
        assertThat(summary.sftTargetMet()).isFalse();
    }

    @Test
    void exportMasksPiiViaSharedRegistry() {
        KbFeedback positive = feedback("fb-1", "m-a2", FeedbackRating.POSITIVE, null, null,
            LocalDateTime.of(2026, 8, 1, 10, 0));
        when(feedbackRepository.findAll(any(Specification.class))).thenReturn(List.of(positive));
        KbMessage q1 = message("m-a1", "s-1", "USER", "我的电话是13812345678，帮我查一下");
        KbMessage a1 = message("m-a2", "s-1", "ASSISTANT", "已为您查询");
        when(messageRepository.findAllById(anyList())).thenReturn(List.of(a1));
        when(messageRepository.findBySessionIdOrderByCreatedAt("s-1")).thenReturn(List.of(q1, a1));

        FeedbackExportSummary summary = service.summary("t-1");
        List<String> lines = service.exportLines("t-1", FeedbackExportService.ExportFormat.SFT);

        assertThat(summary.piiMaskedRecords()).isEqualTo(1);
        assertThat(lines.get(0)).doesNotContain("13812345678");   // PII 明文不落训练数据
    }

    @Test
    void exportFormatParseIsCaseInsensitiveAndRejectsUnknown() {
        assertThat(FeedbackExportService.ExportFormat.parse("SFT"))
            .isEqualTo(FeedbackExportService.ExportFormat.SFT);
        assertThat(FeedbackExportService.ExportFormat.parse(" dpo "))
            .isEqualTo(FeedbackExportService.ExportFormat.DPO);
        assertThat(FeedbackExportService.ExportFormat.parse("kto")).isNull();
        assertThat(FeedbackExportService.ExportFormat.parse(null)).isNull();
    }

    // ── 工具 ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String line) {
        return jsonMapper.readValue(line, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> messages(Map<String, Object> parsed) {
        return (List<Map<String, String>>) parsed.get("messages");
    }
}
