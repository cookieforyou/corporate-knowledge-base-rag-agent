package com.enterprise.kb.ai.agent.service;

import com.enterprise.kb.ai.advisor.AuditTraceAdvisor;
import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具事务链对话服务（设计文档 11.5，任务 3.19 双链路拆分自 ChatService）
 *
 * <p>与 RagChatService（kb-ai-core）职责互补：本服务只服务工具链，签名携带
 * approvedToolCallId（HITL 确认回传）；toolContext 通道仅在此组装——rag 链无
 * 工具消费方，该通道在 RagChatService 签名上物理消除。
 *
 * <p>RetrievalContext 仍经 advisor 参数传递：配额护栏从中读 tenantId、工具审批
 * 绑定从中取身份（双重角色，见 RetrievalContext 注记）。
 */
@Service
public class ToolChatService {

    private final ChatClient toolChatClient;

    /** 显式构造器注入：多 ChatClient Bean 必须 @Qualifier 限定（3.2 @Primary 歧义教训） */
    public ToolChatService(@Qualifier("toolAgentChatClient") ChatClient toolChatClient) {
        this.toolChatClient = toolChatClient;
    }

    /**
     * 确认轮系统指令（3.4 E2E 加固）：工具调用是模型的自主决策，approvedToolCallId
     * 仅经 toolContext 对工具可见、模型上下文不可见——实测确认轮存在模型不调工具的
     * 概率。携带审批凭证时注入本指令令写工具复调确定化；指令不落记忆（system
     * 消息不进记忆窗口）。
     */
    private static final String APPROVAL_CONFIRM_HINT =
        "用户已确认上一轮挂起的写操作。审批凭证（approvedToolCallId）已经审批通道下发至工具上下文，"
            + "请立即调用对应的写工具并携带该凭证完成执行；若工具返回凭证无效或已过期，请如实告知用户重新发起。";

    /** 同步工具事务问答（approvedToolCallId 为 HITL 确认回传，可空） */
    public String chatTool(String query, String sessionId, RetrievalContext retrievalContext,
                           @Nullable String approvedToolCallId) {
        var request = toolChatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext)
                .param(AuditTraceAdvisor.MODE_KEY, "tool"))
            .toolContext(buildToolContext(retrievalContext, approvedToolCallId));
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            request.system(APPROVAL_CONFIRM_HINT);
        }
        return request.call().content();
    }

    /** 流式工具事务问答（approvedToolCallId 为 HITL 确认回传，可空） */
    public Flux<String> chatStreamTool(String query, String sessionId, RetrievalContext retrievalContext,
                                       @Nullable String approvedToolCallId) {
        var request = toolChatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext)
                .param(AuditTraceAdvisor.MODE_KEY, "tool"))
            .toolContext(buildToolContext(retrievalContext, approvedToolCallId));
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            request.system(APPROVAL_CONFIRM_HINT);
        }
        return request.stream().content();
    }

    /**
     * toolContext 通道组装（3.4 复审要素②）：与 advisor 参数独立的第二条通道，
     * 经 ToolCallingChatOptions 注入工具执行的 ToolContext。ChatClient 断言
     * toolContext 无 null 值——userId/approvedToolCallId 条件写入。
     */
    private static Map<String, Object> buildToolContext(RetrievalContext ctx,
                                                        @Nullable String approvedToolCallId) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        // tenantId 由 Controller fail-closed 守卫保证非空；userId 防御性条件写入
        toolContext.put(ToolContextKeys.TENANT_ID, ctx.getTenantId());
        if (ctx.getUserId() != null) {
            toolContext.put(ToolContextKeys.USER_ID, ctx.getUserId());
        }
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            toolContext.put(ToolContextKeys.APPROVED_TOOL_CALL_ID, approvedToolCallId);
        }
        return toolContext;
    }
}
