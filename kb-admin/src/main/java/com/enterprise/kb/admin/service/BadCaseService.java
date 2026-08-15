package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.ReingestRequest;
import com.enterprise.kb.admin.dto.ReingestResult;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.RootCause;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bad Case 运营服务（Phase 4 簇④ 4.7）——根因标注 / Golden Set 回灌 / 处理态闭环。
 *
 * <p><b>根因标注</b>：四分类（{@link RootCause}）落 kb_audit_log.root_cause——
 * 检索未命中 / 改写漂移 / 生成幻觉 / 解析不足，为回灌与链路改进提供方向。
 *
 * <p><b>Golden 回灌通道（Git Ops 形态）</b>：审计行转 Golden 用例写入
 * {@code rag.admin.golden.dir}/badcase-qa.json（默认 kb-eval 语料目录，
 * GoldenDatasetLoader classpath:golden/*.json 扫描域内）——id 确定性
 * {@code bc-{auditLogId}}（重复回灌 upsert 覆写），人工 review 后 git commit，
 * CI 复跑即消费（标注→回灌→CI 复跑闭环）。question 取审计行 query_text
 * （入库时已同款消毒）；期望侧字段（chunk/docs/answer）由运营人工判定填入。
 *
 * <p><b>租户守卫（fail-closed）</b>：审计行不存在/跨租户一律 AUDIT_LOG_NOT_FOUND
 * （不泄露存在性，同 CHUNK_NOT_FOUND 语义）；反馈处理态更新经 message→session
 * 归属校验，跨租户伪装为 FEEDBACK_NOT_FOUND。
 */
@Slf4j
@Service
public class BadCaseService {

    /** 回灌目标文件名（与 golden/*.json 既有语料分文件共存，Git diff 隔离人工标注与回灌件） */
    static final String GOLDEN_FILE_NAME = "badcase-qa.json";

    /** 回灌允许的 Golden 分类（注入样本走 12.6 独立标注流程，不收编此通道） */
    private static final Set<String> REINGEST_CATEGORIES =
        Set.of("FACTOID", "REASONING", "TABLE", "MULTI_DOC", "NEGATIVE");

    private final KbAuditLogRepository auditLogRepository;
    private final KbFeedbackRepository feedbackRepository;
    private final KbMessageRepository messageRepository;
    private final KbSessionRepository sessionRepository;
    private final AiBusinessMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Path goldenDir;

    public BadCaseService(KbAuditLogRepository auditLogRepository,
                          KbFeedbackRepository feedbackRepository,
                          KbMessageRepository messageRepository,
                          KbSessionRepository sessionRepository,
                          AiBusinessMetrics metrics,
                          JsonMapper jsonMapper,
                          @Value("${rag.admin.golden.dir:kb-eval/src/main/resources/golden}") String goldenDir) {
        this.auditLogRepository = auditLogRepository;
        this.feedbackRepository = feedbackRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.goldenDir = Path.of(goldenDir);
    }

    /**
     * 根因标注：四分类写入审计行。
     *
     * @throws BusinessException AUDIT_LOG_NOT_FOUND 行不存在/跨租户；INVALID_ROOT_CAUSE 分类非法
     */
    public String annotate(String tenantId, Long auditLogId, String rootCause) {
        KbAuditLog audit = loadOwnedAudit(tenantId, auditLogId);
        RootCause cause = parseRootCause(rootCause);
        audit.setRootCause(cause.name());
        auditLogRepository.save(audit);
        metrics.recordBadCaseOps("annotate");
        log.info("Bad Case 根因标注: auditLogId={}, rootCause={}", auditLogId, cause);
        return cause.name();
    }

    /**
     * Golden Set 回灌：审计行 → badcase-qa.json（upsert），联动关联反馈 resolved=true。
     *
     * @throws BusinessException AUDIT_LOG_NOT_FOUND / GOLDEN_ENTRY_INVALID / GOLDEN_DIR_UNAVAILABLE
     */
    public ReingestResult reingest(String tenantId, ReingestRequest request) {
        KbAuditLog audit = loadOwnedAudit(tenantId, request.auditLogId());
        String category = request.category() == null || request.category().isBlank()
            ? "FACTOID" : request.category().trim().toUpperCase();
        if (!REINGEST_CATEGORIES.contains(category)) {
            throw new BusinessException("GOLDEN_ENTRY_INVALID",
                "不支持的回灌分类: " + request.category() + "（合法值: " + REINGEST_CATEGORIES + "）");
        }
        String question = audit.getQueryText();
        if (question == null || question.isBlank()) {
            throw new BusinessException("GOLDEN_ENTRY_INVALID", "审计行 query_text 为空，不可回灌");
        }

        String goldenId = "bc-" + audit.getId();
        Path file = goldenDir.resolve(GOLDEN_FILE_NAME);
        List<Map<String, Object>> entries = loadGoldenEntries(file);
        Map<String, Object> entry = buildGoldenEntry(goldenId, category, question, request);
        int existing = indexOfId(entries, goldenId);
        if (existing >= 0) {
            entries.set(existing, entry);
        } else {
            entries.add(entry);
        }
        writeGoldenEntries(file, entries);

        String resolvedFeedbackId = feedbackRepository.findFirstByAuditLogId(audit.getId())
            .map(feedback -> {
                feedback.setResolved(true);
                feedbackRepository.save(feedback);
                return feedback.getId();
            }).orElse(null);

        metrics.recordBadCaseOps("reingest");
        log.info("Bad Case 回灌 Golden Set: goldenId={}, category={}, file={}, resolvedFeedback={}",
            goldenId, category, file, resolvedFeedbackId);
        return new ReingestResult(goldenId, file.toString(), question, category, resolvedFeedbackId);
    }

    /**
     * 反馈处理态更新（Bad Case 人工闭环标记）：归属经 message→session 租户校验。
     *
     * @throws BusinessException FEEDBACK_NOT_FOUND 不存在/跨租户（隐藏存在性）
     */
    public boolean resolveFeedback(String tenantId, String feedbackId, boolean resolved) {
        KbFeedback feedback = feedbackRepository.findById(feedbackId)
            .orElseThrow(() -> new BusinessException("FEEDBACK_NOT_FOUND", "反馈不存在或无权访问"));
        KbMessage message = messageRepository.findById(feedback.getMessageId())
            .orElseThrow(() -> new BusinessException("FEEDBACK_NOT_FOUND", "反馈不存在或无权访问"));
        KbSession session = sessionRepository.findById(message.getSessionId())
            .orElseThrow(() -> new BusinessException("FEEDBACK_NOT_FOUND", "反馈不存在或无权访问"));
        if (!tenantId.equals(session.getTenantId())) {
            throw new BusinessException("FEEDBACK_NOT_FOUND", "反馈不存在或无权访问");
        }
        feedback.setResolved(resolved);
        feedbackRepository.save(feedback);
        log.info("Bad Case 处理态更新: feedbackId={}, resolved={}", feedbackId, resolved);
        return resolved;
    }

    // ── 内部方法 ──

    /** 租户守卫：行不存在/跨租户同一错误码（不泄露存在性） */
    private KbAuditLog loadOwnedAudit(String tenantId, Long auditLogId) {
        KbAuditLog audit = auditLogRepository.findById(auditLogId)
            .orElseThrow(() -> new BusinessException("AUDIT_LOG_NOT_FOUND", "审计记录不存在或无权访问"));
        if (!tenantId.equals(audit.getTenantId())) {
            throw new BusinessException("AUDIT_LOG_NOT_FOUND", "审计记录不存在或无权访问");
        }
        return audit;
    }

    private static RootCause parseRootCause(String rootCause) {
        if (rootCause == null || rootCause.isBlank()) {
            throw new BusinessException("INVALID_ROOT_CAUSE", "rootCause 不能为空");
        }
        try {
            return RootCause.valueOf(rootCause.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ROOT_CAUSE",
                "不支持的根因分类: " + rootCause + "（合法值: "
                    + Arrays.stream(RootCause.values()).map(Enum::name).collect(Collectors.joining("/")) + "）");
        }
    }

    /** Golden 用例载荷：字段序对齐 GoldenQAPair，空期望字段省略（加载器容忍缺失） */
    private static Map<String, Object> buildGoldenEntry(String goldenId, String category,
                                                        String question, ReingestRequest request) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", goldenId);
        entry.put("category", category);
        entry.put("question", question);
        if (request.expectedKeywords() != null && !request.expectedKeywords().isBlank()) {
            entry.put("expectedKeywords", request.expectedKeywords().trim());
        }
        if (request.expectedAnswer() != null && !request.expectedAnswer().isBlank()) {
            entry.put("expectedAnswer", request.expectedAnswer().trim());
        }
        if (request.expectedChunkIds() != null && !request.expectedChunkIds().isEmpty()) {
            entry.put("expectedChunkIds", request.expectedChunkIds().stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim).toList());
        }
        if (request.expectedDocs() != null && !request.expectedDocs().isEmpty()) {
            entry.put("expectedDocs", request.expectedDocs().stream()
                .filter(name -> name != null && !name.isBlank()).map(String::trim).toList());
        }
        return entry;
    }

    private List<Map<String, Object>> loadGoldenEntries(Path file) {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> entries = jsonMapper.readValue(
                Files.readString(file), new TypeReference<List<Map<String, Object>>>() {});
            return entries != null ? new ArrayList<>(entries) : new ArrayList<>();
        } catch (Exception e) {
            throw new BusinessException("GOLDEN_FILE_CORRUPT",
                "Golden 回灌文件解析失败（请人工检查）: " + file + " — " + e.getMessage());
        }
    }

    private void writeGoldenEntries(Path file, List<Map<String, Object>> entries) {
        if (!Files.isDirectory(goldenDir)) {
            throw new BusinessException("GOLDEN_DIR_UNAVAILABLE",
                "Golden 语料目录不存在（检查 rag.admin.golden.dir，须从项目根启动或显式配置）: " + goldenDir);
        }
        try {
            String content = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries) + "\n";
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new BusinessException("GOLDEN_DIR_UNAVAILABLE",
                "Golden 回灌文件写入失败: " + file + " — " + e.getMessage());
        }
    }

    private static int indexOfId(List<Map<String, Object>> entries, String goldenId) {
        for (int i = 0; i < entries.size(); i++) {
            if (goldenId.equals(entries.get(i).get("id"))) {
                return i;
            }
        }
        return -1;
    }
}
