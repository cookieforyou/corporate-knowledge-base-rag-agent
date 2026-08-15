package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.AuditLogPage;
import com.enterprise.kb.admin.dto.AuditLogView;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.RootCause;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计日志查询服务（Phase 4 簇④ 4.7）——Bad Case 运营闭环的查询入口。
 *
 * <p>租户过滤恒在（仓储层 tenantId 必传，fail-closed 纪律）；过滤项传 null 不生效。
 * 视图组装附带关联反馈的期望回答（kb_feedback.audit_log_id 批量联查），
 * 供前端 Golden 回灌对话框预填。
 */
@Slf4j
@Service
public class AuditLogQueryService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private static final Set<String> FEEDBACK_FILTERS = Set.of("POSITIVE", "NEGATIVE");
    private static final Set<String> STATUS_FILTERS = Set.of("SUCCESS", "REJECTED", "ERROR");
    private static final Set<String> ROOT_CAUSE_FILTERS =
        java.util.Arrays.stream(RootCause.values()).map(Enum::name).collect(Collectors.toSet());

    private final KbAuditLogRepository auditLogRepository;
    private final KbFeedbackRepository feedbackRepository;

    public AuditLogQueryService(KbAuditLogRepository auditLogRepository,
                                KbFeedbackRepository feedbackRepository) {
        this.auditLogRepository = auditLogRepository;
        this.feedbackRepository = feedbackRepository;
    }

    /**
     * 多选项分页查询。from/to 为 ISO 本地时间（yyyy-MM-ddTHH:mm:ss），null/空白不限。
     *
     * @throws BusinessException INVALID_TIME_FORMAT 时间解析失败；INVALID_FILTER 枚举过滤值非法
     */
    public AuditLogPage search(String tenantId, String from, String to,
                               String userId, String sessionId, String feedback,
                               String status, String rootCause, Boolean annotated,
                               Integer page, Integer size) {
        LocalDateTime fromTime = parseTime(from, "from");
        LocalDateTime toTime = parseTime(to, "to");
        String feedbackFilter = normalizeEnum(feedback, FEEDBACK_FILTERS, "feedback", "INVALID_FILTER");
        String statusFilter = normalizeEnum(status, STATUS_FILTERS, "status", "INVALID_FILTER");
        String rootCauseFilter = normalizeEnum(rootCause, ROOT_CAUSE_FILTERS, "rootCause", "INVALID_FILTER");
        int cappedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageIndex = page == null || page < 0 ? 0 : page;

        Page<KbAuditLog> result = auditLogRepository.search(tenantId, fromTime, toTime,
            blankToNull(userId), blankToNull(sessionId),
            feedbackFilter, statusFilter, rootCauseFilter, annotated,
            PageRequest.of(pageIndex, cappedSize));

        Map<Long, String> expectedAnswerByAuditId = expectedAnswers(result.getContent());
        List<AuditLogView> items = result.getContent().stream()
            .map(audit -> AuditLogView.from(audit, expectedAnswerByAuditId.get(audit.getId())))
            .toList();
        return new AuditLogPage(items, result.getTotalElements(), pageIndex, cappedSize);
    }

    // ── 内部方法 ──

    private static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("INVALID_TIME_FORMAT",
                "时间参数 " + field + " 须为 ISO 格式（yyyy-MM-ddTHH:mm:ss）: " + value);
        }
    }

    /** 空白 → null 不过滤；非空须命中合法集（大写归一），否则抛错码 */
    private static String normalizeEnum(String value, Set<String> allowed, String field, String errorCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(errorCode,
                "不支持的 " + field + " 过滤值: " + value + "（合法值: " + allowed + "）");
        }
        return normalized;
    }

    /** 页内审计行关联反馈的期望回答（audit_log_id → expectedAnswer，无关联不出现） */
    private Map<Long, String> expectedAnswers(List<KbAuditLog> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Long> auditIds = rows.stream().map(KbAuditLog::getId).toList();
        Map<Long, String> answers = new HashMap<>();
        for (KbFeedback feedback : feedbackRepository.findByAuditLogIdIn(auditIds)) {
            if (feedback.getAuditLogId() != null && feedback.getExpectedAnswer() != null) {
                answers.putIfAbsent(feedback.getAuditLogId(), feedback.getExpectedAnswer());
            }
        }
        return answers;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
