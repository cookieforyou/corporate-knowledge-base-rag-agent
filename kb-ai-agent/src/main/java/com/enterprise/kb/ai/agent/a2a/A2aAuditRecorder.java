package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A2A 端点入口轻量审计（Phase5簇⑥ 批2，McpAuditRecorder/DingTalkAuditRecorder 同构）
 *
 * <p><b>与对话链审计的关系</b>：chatRag 全链自带 AuditTraceAdvisor 审计行
 * （mode=rag，租户/用户/traceId/token 全链快照）——本组件是<b>入口域标记</b>：
 * mode=a2a 轻行区分「本条 rag 问答来自 A2A 协议端点」，入口侧独立可查。
 *
 * <p><b>两档形态（MCP B3 同款）</b>：结构化日志恒开（租户/用户/载荷摘要）；
 * DB 轻行（{@code rag.a2a.audit.enabled} 开则落）——kb_audit_log 最小投影
 * mode=a2a + query_text 脱敏 + tool_calls=[{"tool":"chat"}]，默认关。
 *
 * <p><b>容错</b>：旁路数据——落库失败只 warn 丢弃，绝不击穿请求处理；
 * 异步经 auditExecutor（与对话链审计共享执行器）。traceId 复用当轮
 * RetrievalContext 值——入口轻行与对话链审计行凭 traceId 关联。
 */
@Slf4j
@Component
public class A2aAuditRecorder {

    static final String AUDIT_MODE = "a2a";

    private final KbAuditLogRepository auditLogRepository;
    private final AsyncTaskExecutor auditExecutor;
    private final JsonMapper jsonMapper;
    private final PiiRecognizerRegistry piiRegistry;
    private final boolean dbAuditEnabled;

    public A2aAuditRecorder(KbAuditLogRepository auditLogRepository,
                            @Qualifier(Constants.BeanNames.AUDIT_EXECUTOR) AsyncTaskExecutor auditExecutor,
                            JsonMapper jsonMapper,
                            PiiRecognizerRegistry piiRegistry,
                            A2aProperties properties) {
        this.auditLogRepository = auditLogRepository;
        this.auditExecutor = auditExecutor;
        this.jsonMapper = jsonMapper;
        this.piiRegistry = piiRegistry;
        this.dbAuditEnabled = properties.getAudit().isEnabled();
        log.info("A2A 端点审计装配: dbAuditEnabled={}", dbAuditEnabled);
    }

    /**
     * 记录一次 A2A 请求入口（限流通过后调用——访问审计语义）。
     *
     * @param queryText 消息文本（parts 拼接），经 PII 脱敏后落库
     * @param ctx       身份上下文（tenantId/userId 来自 JWT claims）
     */
    public void record(String queryText, RetrievalContext ctx) {
        String tenantId = ctx.getTenantId();
        String userId = ctx.getUserId();
        log.info("A2A 请求: tenant={}, user={}, argDigest={}",
            tenantId, userId, digestOf(queryText));
        if (!dbAuditEnabled) {
            return;
        }
        String masked = piiRegistry.mask(queryText);
        String traceId = ctx.getTraceId();
        auditExecutor.execute(() -> persistSafely(masked, tenantId, userId, traceId));
    }

    private void persistSafely(String maskedQuery, String tenantId, String userId, String traceId) {
        try {
            KbAuditLog audit = new KbAuditLog();
            audit.setTraceId(traceId == null ? UUID.randomUUID().toString() : traceId);
            audit.setMode(AUDIT_MODE);
            audit.setTenantId(tenantId);
            audit.setUserId(userId);
            audit.setQueryText(maskedQuery == null ? "" : maskedQuery);
            audit.setToolCalls(toJsonOrNull(List.of(Map.of("tool", "chat"))));
            audit.setStatus(Constants.AuditStatus.SUCCESS);
            auditLogRepository.save(audit);
        } catch (Exception e) {
            log.warn("A2A 审计落库失败，丢弃（旁路数据，不影响请求处理）: {}", e.getMessage());
        }
    }

    /** 日志面载荷摘要：前 40 字符——可定位不泄露 */
    private static String digestOf(String argument) {
        if (argument == null) {
            return "";
        }
        return argument.length() <= 40 ? argument : argument.substring(0, 40) + "...";
    }

    private String toJsonOrNull(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}
