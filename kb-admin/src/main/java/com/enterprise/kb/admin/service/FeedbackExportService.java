package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.FeedbackExportSummary;
import com.enterprise.kb.commons.security.pii.PiiMaskResult;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import com.enterprise.kb.domain.spec.FeedbackSpecs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 反馈微调数据导出服务（簇② 5.10 批4，设计 16.6 反馈闭环「偏好数据导出」落点）。
 *
 * <p><b>双格式派生规则（确定性）</b>：
 * <ul>
 *   <li><b>SFT 正向采纳通道</b>：👍 反馈 →（问题，系统原回答）单轮对；</li>
 *   <li><b>SFT 用户订正通道</b>：👎 且附 expectedAnswer →（问题，用户期望回答）；</li>
 *   <li><b>DPO 偏好对</b>：👎 + expectedAnswer + 原回答齐备 → 同一问题下
 *       chosen=用户订正 / rejected=系统原回答——二元反馈天然成对，无需跨会话配对。</li>
 * </ul>
 *
 * <p><b>质量过滤</b>：审计三态联查（经 3.17 回填的 audit_log_id），REJECTED/ERROR
 * 结局的对话不入训练材料（护栏替换话术/异常响应无训练价值）；审计缺失（关闭或未落）
 * 不阻断导出。导出文本经共享 {@link PiiRecognizerRegistry} 同款掩码——与对话链/ETL/
 * 审计同一 Bean 实例，训练数据不携 PII 明文。
 *
 * <p><b>格式参考（不绑定平台）</b>：对齐百炼调优数据上传规则的 ChatML 形态——
 * SFT 为 {@code {"messages":[user,assistant]}}（不烘焙 system，送训侧自行前置），
 * DPO 为 {@code {"messages":[user],"chosen":assistant,"rejected":assistant}}。
 * 门槛 SFT≥100 / DPO≥50 对只在概览报告对照，不阻断导出。
 *
 * <p><b>租户收敛</b>：复用 {@link FeedbackSpecs#tenantFeedback} 两级子查询
 * （message→session），跨租户反馈零泄露；导出顺序按 createdAt 正序确定性稳定。
 */
@Slf4j
@Service
public class FeedbackExportService {

    /** 百炼微调通道数据量建议线（只报告不门禁） */
    static final int SFT_TARGET = 100;
    static final int DPO_TARGET = 50;

    /** 审计三态中不入训练材料的结局 */
    private static final Set<String> AUDIT_NOT_CLEAN = Set.of("REJECTED", "ERROR");

    private final KbFeedbackRepository feedbackRepository;
    private final KbMessageRepository messageRepository;
    private final KbSessionRepository sessionRepository;
    private final KbAuditLogRepository auditLogRepository;
    private final PiiRecognizerRegistry piiRegistry;
    private final JsonMapper jsonMapper;

    public FeedbackExportService(KbFeedbackRepository feedbackRepository,
                                 KbMessageRepository messageRepository,
                                 KbSessionRepository sessionRepository,
                                 KbAuditLogRepository auditLogRepository,
                                 PiiRecognizerRegistry piiRegistry,
                                 JsonMapper jsonMapper) {
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.piiRegistry = piiRegistry;
        this.jsonMapper = jsonMapper;
    }

    // ── 对外入口 ──

    /** 导出概览（dry-run）：计数 + 门槛对照，零内容输出 */
    public FeedbackExportSummary summary(String tenantId) {
        Buckets buckets = collect(tenantId);
        return toSummary(buckets);
    }

    /**
     * 导出 JSONL 行（format=sft|dpo）。UTF-8 逐行 JSON，
     * 顺序按反馈创建时刻正序确定性稳定。
     */
    public List<String> exportLines(String tenantId, ExportFormat format) {
        Buckets buckets = collect(tenantId);
        List<String> lines = format == ExportFormat.SFT
            ? buckets.sftPairs().stream().map(p -> sftLine(p, jsonMapper)).toList()
            : buckets.dpoPairs().stream().map(p -> dpoLine(p, jsonMapper)).toList();
        log.info("反馈微调数据导出: tenant={}, format={}, records={}", tenantId, format, lines.size());
        return lines;
    }

    /** 导出格式 */
    public enum ExportFormat {
        SFT, DPO;

        /** 解析（大小写不敏感），非法值返回 null 由调用侧转业务异常 */
        public static ExportFormat parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return ExportFormat.valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // ── 采集与分类 ──

    /** 导出候选行：反馈 + 会话上下文 + 审计结局（已消毒） */
    record ExportCandidate(String feedbackId, FeedbackRating rating, String query,
                           String answer, String expectedAnswer, String auditStatus) {
    }

    /** SFT 单轮对（fromCorrection 区分订正/采纳通道） */
    record SftPair(String question, String answer, boolean fromCorrection) {
    }

    /** DPO 偏好对：同一问题下 chosen（用户订正）优于 rejected（系统原回答） */
    record DpoPair(String question, String chosen, String rejected) {
    }

    /** 分类结果桶：可导出对 + 跳过计数（概览报告素材） */
    record Buckets(List<SftPair> sftPairs, List<DpoPair> dpoPairs,
                   int positiveFeedback, int negativeFeedback, int negativeWithExpectedAnswer,
                   int missingConversation, int auditNotClean, int negativeWithoutCorrection,
                   int piiMasked) {
    }

    /**
     * 租户域采集：反馈 → 消息/会话上下文 → 审计结局，PII 消毒后转候选行。
     * 导出顺序按 createdAt 正序（查询侧倒序，此处反转保证训练集跨导出稳定）。
     */
    private Buckets collect(String tenantId) {
        List<KbFeedback> rows = feedbackRepository.findAll(
            FeedbackSpecs.tenantFeedback(tenantId, null, null));
        List<KbFeedback> ordered = rows.stream()
            .sorted(Comparator.comparing(KbFeedback::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(KbFeedback::getId))
            .toList();

        Map<String, KbMessage> answerById = batchLoadMessages(ordered);
        Map<String, List<KbMessage>> timelineBySession = batchLoadTimelines(answerById.values());
        Map<Long, String> auditStatusById = batchLoadAuditStatus(ordered);

        int piiMasked = 0;
        List<ExportCandidate> candidates = new ArrayList<>(ordered.size());
        for (KbFeedback row : ordered) {
            KbMessage answerMessage = answerById.get(row.getMessageId());
            String query = answerMessage != null
                ? nearestUserQuery(timelineBySession.getOrDefault(answerMessage.getSessionId(), List.of()),
                    answerMessage)
                : null;
            String answer = answerMessage != null ? answerMessage.getContent() : null;

            // PII 消毒（共享注册表同款）：三字段任一命中则该记录计入掩码数
            PiiMaskResult maskedQuery = piiRegistry.maskWithReport(query);
            PiiMaskResult maskedAnswer = piiRegistry.maskWithReport(answer);
            PiiMaskResult maskedExpected = piiRegistry.maskWithReport(row.getExpectedAnswer());
            if (!maskedQuery.hitTypes().isEmpty() || !maskedAnswer.hitTypes().isEmpty()
                || !maskedExpected.hitTypes().isEmpty()) {
                piiMasked++;
            }

            candidates.add(new ExportCandidate(row.getId(), row.getRating(),
                blankToNull(maskedQuery.text()), blankToNull(maskedAnswer.text()),
                blankToNull(maskedExpected.text()),
                row.getAuditLogId() != null ? auditStatusById.get(row.getAuditLogId()) : null));
        }
        return classify(candidates, piiMasked);
    }

    /**
     * 候选行分类（纯函数，单测直测）——派生规则与跳过计数一次遍历完成。
     */
    static Buckets classify(List<ExportCandidate> candidates, int piiMasked) {
        List<SftPair> sftPairs = new ArrayList<>();
        List<DpoPair> dpoPairs = new ArrayList<>();
        int positive = 0;
        int negative = 0;
        int negativeWithExpected = 0;
        int missingConversation = 0;
        int auditNotClean = 0;
        int negativeWithoutCorrection = 0;

        for (ExportCandidate c : candidates) {
            if (c.rating() == FeedbackRating.POSITIVE) {
                positive++;
            } else {
                negative++;
            }
            if (c.query() == null) {
                missingConversation++;
                continue;
            }
            if (c.auditStatus() != null && AUDIT_NOT_CLEAN.contains(c.auditStatus())) {
                auditNotClean++;
                continue;
            }
            if (c.rating() == FeedbackRating.POSITIVE) {
                if (c.answer() == null) {
                    missingConversation++;
                    continue;
                }
                sftPairs.add(new SftPair(c.query(), c.answer(), false));
            } else {
                if (c.expectedAnswer() == null) {
                    negativeWithoutCorrection++;
                    continue;
                }
                negativeWithExpected++;
                sftPairs.add(new SftPair(c.query(), c.expectedAnswer(), true));
                if (c.answer() != null) {
                    dpoPairs.add(new DpoPair(c.query(), c.expectedAnswer(), c.answer()));
                } else {
                    missingConversation++;
                }
            }
        }
        return new Buckets(List.copyOf(sftPairs), List.copyOf(dpoPairs),
            positive, negative, negativeWithExpected,
            missingConversation, auditNotClean, negativeWithoutCorrection, piiMasked);
    }

    // ── JSONL 行渲染（纯函数）──

    /** SFT 行：{@code {"messages":[user,assistant]}}——不烘焙 system，送训侧前置 */
    static String sftLine(SftPair pair, JsonMapper json) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("messages", List.of(
            roleMessage("user", pair.question()),
            roleMessage("assistant", pair.answer())));
        return json.writeValueAsString(line);
    }

    /** DPO 行：{@code {"messages":[user],"chosen":assistant,"rejected":assistant}} */
    static String dpoLine(DpoPair pair, JsonMapper json) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("messages", List.of(roleMessage("user", pair.question())));
        line.put("chosen", roleMessage("assistant", pair.chosen()));
        line.put("rejected", roleMessage("assistant", pair.rejected()));
        return json.writeValueAsString(line);
    }

    private static Map<String, Object> roleMessage(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    /** JSONL 载荷拼装：逐行 \n 结尾，空集返回零字节 */
    public static byte[] toJsonlBytes(List<String> lines) {
        if (lines.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ── 概览 ──

    static FeedbackExportSummary toSummary(Buckets b) {
        int sftRecords = b.sftPairs().size();
        int sftFromCorrection = (int) b.sftPairs().stream().filter(SftPair::fromCorrection).count();
        return new FeedbackExportSummary(
            b.positiveFeedback() + b.negativeFeedback(),
            b.positiveFeedback(), b.negativeFeedback(), b.negativeWithExpectedAnswer(),
            sftRecords, sftRecords - sftFromCorrection, sftFromCorrection,
            b.dpoPairs().size(),
            b.missingConversation(), b.auditNotClean(), b.negativeWithoutCorrection(),
            b.piiMasked(),
            SFT_TARGET, sftRecords >= SFT_TARGET,
            DPO_TARGET, b.dpoPairs().size() >= DPO_TARGET);
    }

    // ── 批量装载（控 N+1，同 FeedbackService.attachConversation 形态）──

    private Map<String, KbMessage> batchLoadMessages(List<KbFeedback> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, KbMessage> byId = new HashMap<>();
        messageRepository.findAllById(rows.stream().map(KbFeedback::getMessageId).toList())
            .forEach(m -> byId.put(m.getId(), m));
        return byId;
    }

    private Map<String, List<KbMessage>> batchLoadTimelines(Collection<KbMessage> answers) {
        Set<String> sessionIds = answers.stream()
            .map(KbMessage::getSessionId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<KbMessage>> timelines = new HashMap<>();
        for (String sessionId : sessionIds) {
            timelines.put(sessionId, messageRepository.findBySessionIdOrderByCreatedAt(sessionId));
        }
        return timelines;
    }

    private Map<Long, String> batchLoadAuditStatus(List<KbFeedback> rows) {
        List<Long> auditIds = rows.stream()
            .map(KbFeedback::getAuditLogId).filter(Objects::nonNull).distinct().toList();
        if (auditIds.isEmpty()) {
            return Map.of();
        }
        return auditLogRepository.findAllById(auditIds).stream()
            .collect(Collectors.toMap(KbAuditLog::getId,
                a -> a.getStatus() != null ? a.getStatus() : "",
                (a, b) -> a));
    }

    /** 时间序中该助手消息之前最近的 USER 消息（同 FeedbackService 口径） */
    private static String nearestUserQuery(List<KbMessage> timeline, KbMessage assistantMessage) {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            KbMessage m = timeline.get(i);
            if (m.getId().equals(assistantMessage.getId())) {
                for (int j = i - 1; j >= 0; j--) {
                    if ("USER".equals(timeline.get(j).getRole())) {
                        return timeline.get(j).getContent();
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
