package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.api.dto.FeedbackItem;
import com.enterprise.kb.api.dto.FeedbackRequest;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import com.enterprise.kb.domain.spec.FeedbackSpecs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 用户反馈收集服务（3.17，设计文档 16.6 反馈闭环采集端）
 *
 * <p>闭环链路：前端 👍/👎（messageId/traceId 来自 SSE DONE 帧 / 同步响应）→
 * kb_feedback 落库（按 messageId+userId upsert，可更改评价）→ kb_audit_log.feedback
 * 回填 + audit_log_id 关联（Bad Case → 检索快照/prompt/回答全链路可联查）→
 * AiBusinessMetrics rag.feedback.like/dislike 计数（3.13 接线点）。
 *
 * <p><b>归档竞态容忍</b>：kb_message 为异步归档（ChatSessionService @Async），
 * 极端时序下反馈先于消息行落库——存在性检查带短窗口轮询等待（默认 2s，
 * {@code rag.feedback.message-wait-millis} 可调），超窗按不存在拒绝。
 *
 * <p><b>租户隔离 fail-closed</b>：kb_feedback 无租户列，归属经 message→session
 * 解析校验；跨租户/跨用户引用一律按 MESSAGE_NOT_FOUND 隐藏（不泄露存在性，
 * 与 HybridDocumentRetriever 空结果策略同款）。
 */
@Slf4j
@Service
public class FeedbackService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final KbFeedbackRepository feedbackRepository;
    private final KbMessageRepository messageRepository;
    private final KbSessionRepository sessionRepository;
    private final KbAuditLogRepository auditLogRepository;
    private final AiBusinessMetrics metrics;
    private final JsonMapper jsonMapper;
    private final long messageWaitMillis;

    public FeedbackService(KbFeedbackRepository feedbackRepository,
                           KbMessageRepository messageRepository,
                           KbSessionRepository sessionRepository,
                           KbAuditLogRepository auditLogRepository,
                           AiBusinessMetrics metrics,
                           JsonMapper jsonMapper,
                           @Value("${rag.feedback.message-wait-millis:2000}") long messageWaitMillis) {
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.auditLogRepository = auditLogRepository;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.messageWaitMillis = messageWaitMillis;
    }

    /**
     * 提交反馈（upsert 幂等）：同一用户对同一回答仅一条，可更改评价/补充期望回答。
     *
     * @throws BusinessException INVALID_FEEDBACK 参数非法；MESSAGE_NOT_FOUND 消息不存在/
     *                           归档超窗/跨租户跨用户引用（隐藏存在性）
     */
    public KbFeedback submit(String tenantId, String userId, FeedbackRequest request) {
        FeedbackRating rating = parseRating(request.rating());
        String messageId = request.messageId();
        if (messageId == null || messageId.isBlank()) {
            throw new BusinessException("INVALID_FEEDBACK", "反馈缺少 messageId");
        }

        KbMessage message = awaitMessage(messageId);
        KbSession session = sessionRepository.findById(message.getSessionId())
            .orElseThrow(() -> new BusinessException("MESSAGE_NOT_FOUND", "消息归属会话不存在"));
        // 租户隔离 fail-closed：跨租户/跨用户引用伪装为不存在
        if (!Objects.equals(session.getTenantId(), tenantId)
            || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException("MESSAGE_NOT_FOUND", "消息不存在或无权访问");
        }

        KbFeedback feedback = feedbackRepository.findByMessageIdAndUserId(messageId, userId)
            .orElseGet(() -> {
                KbFeedback created = new KbFeedback();
                created.setId(UUID.randomUUID().toString());
                created.setMessageId(messageId);
                created.setUserId(userId);
                created.setResolved(false);
                return created;
            });
        boolean isNew = feedback.getCreatedAt() == null;
        feedback.setRating(rating);
        feedback.setExpectedAnswer(blankToNull(request.expectedAnswer()));
        feedback.setFeedbackTags(toJsonOrNull(request.tags()));
        backfillAudit(feedback, request.traceId(), rating);
        KbFeedback saved = feedbackRepository.save(feedback);
        // 指标按受理提交计数（3.13 接线点）；更改评价产生新事件如实反映
        metrics.recordFeedback(rating == FeedbackRating.POSITIVE);
        log.info("用户反馈已记录: messageId={}, rating={}, upsert={}", messageId, rating, isNew ? "新增" : "更新");
        return saved;
    }

    /**
     * Bad Case 查询（验收 #13）：租户可见域内反馈列表（rating/resolved 可选过滤），
     * 附带原始问答文本。resolved 标记更新归簇④（PUT /api/v1/admin/feedback/{id}/resolved）。
     *
     * <p>v2.35：查询经 {@link FeedbackSpecs} 动态谓词执行（原 @Query 可选参数
     * PG 预编译类型推断缺陷修正）。
     */
    public List<FeedbackItem> search(String tenantId, String rating, Boolean resolved, Integer limit) {
        FeedbackRating ratingFilter = rating == null || rating.isBlank() ? null : parseRating(rating);
        int capped = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<KbFeedback> rows = feedbackRepository.findAll(
            FeedbackSpecs.tenantFeedback(tenantId, ratingFilter, resolved),
            PageRequest.of(0, capped)).getContent();
        return attachConversation(rows);
    }

    // ── 内部机制 ──

    /**
     * 等待归档消息可见：异步归档通常毫秒级完成，短窗口轮询兜底极端时序；
     * waitMillis=0 时退化为单次检查（测试形态）。
     */
    private KbMessage awaitMessage(String messageId) {
        long deadline = System.currentTimeMillis() + messageWaitMillis;
        while (true) {
            if (messageRepository.existsById(messageId)) {
                return messageRepository.findById(messageId).orElseThrow(
                    () -> new BusinessException("MESSAGE_NOT_FOUND", "消息不存在: " + messageId));
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new BusinessException("MESSAGE_NOT_FOUND", "消息不存在或归档未完成: " + messageId);
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("MESSAGE_NOT_FOUND", "消息查询被中断: " + messageId);
            }
        }
    }

    /** rating 解析：大小写不敏感，非法值 400 INVALID_FEEDBACK */
    private static FeedbackRating parseRating(String rating) {
        if (rating == null || rating.isBlank()) {
            throw new BusinessException("INVALID_FEEDBACK", "反馈缺少 rating");
        }
        try {
            return FeedbackRating.valueOf(rating.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_FEEDBACK",
                "不支持的反馈评分: " + rating + "（仅支持 POSITIVE|NEGATIVE）");
        }
    }

    /**
     * 审计关联回填（§11.6.3 留空列）：trace_id 经 idx_audit_trace 确定性定位。
     * 旁路容错：审计行缺失（异步未落/rag.audit.enabled=false/落库失败）静默跳过，
     * 反馈主数据不受影响（audit_log_id 可空）。
     */
    private void backfillAudit(KbFeedback feedback, String traceId, FeedbackRating rating) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        try {
            auditLogRepository.findFirstByTraceId(traceId).ifPresent(audit -> {
                feedback.setAuditLogId(audit.getId());
                audit.setFeedback(rating.name());
                auditLogRepository.save(audit);
            });
        } catch (Exception e) {
            log.warn("反馈回填审计失败（旁路数据，反馈已落库）: traceId={}, {}", traceId, e.getMessage());
        }
    }

    /** 附原始问答文本：反馈消息即助手回答，用户问题取同会话时间序最近的 USER 消息 */
    private List<FeedbackItem> attachConversation(List<KbFeedback> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, KbMessage> answerById = new HashMap<>();
        messageRepository.findAllById(rows.stream().map(KbFeedback::getMessageId).toList())
            .forEach(m -> answerById.put(m.getId(), m));

        // 会话消息按 sessionId 分组拉取（去重控制查询次数）
        Set<String> sessionIds = new LinkedHashSet<>();
        answerById.values().forEach(m -> sessionIds.add(m.getSessionId()));
        Map<String, List<KbMessage>> sessionMessages = new HashMap<>();
        for (String sessionId : sessionIds) {
            sessionMessages.put(sessionId, messageRepository.findBySessionIdOrderByCreatedAt(sessionId));
        }

        List<FeedbackItem> items = new ArrayList<>(rows.size());
        for (KbFeedback row : rows) {
            KbMessage answer = answerById.get(row.getMessageId());
            String query = null;
            if (answer != null) {
                List<KbMessage> timeline = sessionMessages.getOrDefault(answer.getSessionId(), List.of());
                query = nearestUserQuery(timeline, answer);
            }
            items.add(new FeedbackItem(
                row.getId(), row.getMessageId(),
                answer != null ? answer.getSessionId() : null,
                row.getRating().name(), row.getExpectedAnswer(),
                parseTags(row.getFeedbackTags()), row.getResolved(), row.getCreatedAt(),
                row.getAuditLogId(), query, answer != null ? answer.getContent() : null));
        }
        return items;
    }

    /** 时间序中该助手消息之前最近的 USER 消息（多轮对话每轮一问一答） */
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

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(jsonMapper.readValue(tagsJson, String[].class));
        } catch (Exception e) {
            log.warn("反馈标签解析失败，置空: {}", e.getMessage());
            return List.of();
        }
    }

    private String toJsonOrNull(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("反馈标签序列化失败，置空: {}", e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
