package com.enterprise.kb.ai.agent.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.security.TextSanitizer;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 只读工具轻量审计（安全簇② B3，2026-08-17）
 *
 * <p><b>动因</b>：search/get_document 不经 advisor 链，无 AuditTraceAdvisor
 * 审计行——只读高频调用在 kb_audit_log 完全不可见。本组件补位。
 *
 * <p><b>两档形态（专项方案 §4.2 B3 定案）</b>：
 * <ul>
 *   <li><b>结构化日志（恒开）</b>：租户/用户/工具/载荷摘要入日志面——零成本、
 *       零 DB 写入，日志采集即可审计；</li>
 *   <li><b>DB 轻行（{@code rag.mcp.audit.enabled} 开则落）</b>：kb_audit_log
 *       最小投影——mode=mcp + tool_calls JSON 记工具名 + query_text 脱敏载荷，
 *       无检索快照/token/记忆字段（区别于对话链全链快照）。默认关：先经日志面
 *       观察体量与价值，再定 DB 口径。</li>
 * </ul>
 *
 * <p><b>容错</b>：审计为旁路数据——落库失败只 warn 丢弃，绝不击穿工具调用
 * （AuditTraceAdvisor / ChatSessionService 归档同款哲学）；异步经 auditExecutor
 * （与对话链审计共享执行器）。</p>
 */
@Slf4j
@Component
public class McpAuditRecorder {

    private final KbAuditLogRepository auditLogRepository;
    private final AsyncTaskExecutor auditExecutor;
    private final JsonMapper jsonMapper;
    private final boolean dbAuditEnabled;

    public McpAuditRecorder(KbAuditLogRepository auditLogRepository,
                            @Qualifier("auditExecutor") AsyncTaskExecutor auditExecutor,
                            JsonMapper jsonMapper,
                            @Value("${rag.mcp.audit.enabled:false}") boolean dbAuditEnabled) {
        this.auditLogRepository = auditLogRepository;
        this.auditExecutor = auditExecutor;
        this.jsonMapper = jsonMapper;
        this.dbAuditEnabled = dbAuditEnabled;
        log.info("MCP 只读工具审计装配: dbAuditEnabled={}", dbAuditEnabled);
    }

    /**
     * 记录一次只读工具调用（限流通过后、工具执行前调用——访问审计语义）。
     *
     * @param tool      工具名（search / get_document）
     * @param argument  载荷（检索问题 / 文档 ID），经 PII 脱敏后落库
     * @param ctx       身份上下文（McpIdentityGuard fail-closed 后租户/用户必非空）
     */
    public void record(String tool, String argument, RetrievalContext ctx) {
        String tenantId = ctx.getTenantId();
        String userId = ctx.getUserId();
        log.info("MCP 只读工具调用: tenant={}, user={}, tool={}, argDigest={}",
            tenantId, userId, tool, digestOf(argument));
        if (!dbAuditEnabled) {
            return;
        }
        String maskedArgument = TextSanitizer.maskPii(argument);
        auditExecutor.execute(() -> persistSafely(tool, maskedArgument, tenantId, userId));
    }

    private void persistSafely(String tool, String maskedArgument, String tenantId, String userId) {
        try {
            KbAuditLog audit = new KbAuditLog();
            audit.setTraceId(UUID.randomUUID().toString());
            audit.setMode("mcp");
            audit.setTenantId(tenantId);
            audit.setUserId(userId);
            audit.setQueryText(maskedArgument == null ? "" : maskedArgument);
            audit.setToolCalls(toJsonOrNull(List.of(Map.of("tool", tool))));
            audit.setStatus("SUCCESS");
            auditLogRepository.save(audit);
        } catch (Exception e) {
            log.warn("MCP 审计落库失败，丢弃（旁路数据，不影响工具调用）: {}", e.getMessage());
        }
    }

    /** 日志面载荷摘要：前 40 字符——可定位不泄露（完整载荷经 DB 档/链路侧持有） */
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
