package com.enterprise.kb.ai.service;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.tool.ToolContextKeys;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 对话服务 — 封装 Agent ChatClient 调用
 *
 * <p>3.1 起走 {@code agentChatClient}（记忆 + 检索链路）；kb-eval 仍直注
 * {@code chatClient} Bean（纯 RAG，评估基线不受影响）。
 *
 * <p><b>会话 ID 参数链</b>：sessionId 由 Controller 经 advisor 参数
 * （{@link ChatMemory#CONVERSATION_ID}）传入记忆 Advisor——与 RetrievalContext
 * 同款参数化机制（不用 @RequestScope/ThreadLocal；MVC 异步请求完结后作用域
 * 代理不可解析，流式生命周期内必失效）。缺失会话 ID 时
 * BaseChatMemoryAdvisor 为硬断言失败，故 Controller 必须保证非空
 * （请求未携带时由 Controller 生成）。
 *
 * <p>调用方（kb-api Controller）在请求线程创建并填充 {@link RetrievalContext}
 * （租户/用户身份），同样经 advisor 参数传入检索组件——同步与流式一致。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;

    /** 显式构造器注入：Lombok 不复制字段 @Qualifier 至构造参数，双 ChatClient Bean 下须显式限定 */
    public ChatService(@Qualifier("agentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 确认轮系统指令（3.4 E2E 加固）：工具调用是模型的自主决策，approvedToolCallId
     * 仅经 toolContext 对工具可见、模型上下文不可见——E2E 实测确认轮存在模型不调
     * 工具（被检索上下文带偏成普通 RAG 作答）的概率。携带审批凭证时注入本指令，
     * 令写工具复调确定化；指令不落记忆（system 消息不进记忆窗口）。
     */
    private static final String APPROVAL_CONFIRM_HINT =
        "用户已确认上一轮挂起的写操作。审批凭证（approvedToolCallId）已经审批通道下发至工具上下文，"
            + "请立即调用对应的写工具并携带该凭证完成执行；若工具返回凭证无效或已过期，请如实告知用户重新发起。";

    /** 同步多轮 RAG 问答（approvedToolCallId 为 HITL 确认回传，可空） */
    public String chat(String query, String sessionId, RetrievalContext retrievalContext,
                       @Nullable String approvedToolCallId) {
        var request = chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .toolContext(buildToolContext(retrievalContext, approvedToolCallId));
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            request.system(APPROVAL_CONFIRM_HINT);
        }
        return request.call().content();
    }

    /** 流式多轮 RAG 问答（approvedToolCallId 为 HITL 确认回传，可空） */
    public Flux<String> chatStream(String query, String sessionId, RetrievalContext retrievalContext,
                                   @Nullable String approvedToolCallId) {
        var request = chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .toolContext(buildToolContext(retrievalContext, approvedToolCallId));
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            request.system(APPROVAL_CONFIRM_HINT);
        }
        return request.stream().content();
    }

    /**
     * toolContext 通道组装（3.4 复审要素②）：与 advisor 参数独立的第二条通道，
     * 经 ToolCallingChatOptions 注入工具执行的 ToolContext。身份供写工具审批绑定
     * 校验；RetrievalContext 实例供工具写回调用记录（SSE TOOL_CALL 数据源）。
     * ChatClient 断言 toolContext 无 null 值——approvedToolCallId 条件写入。
     */
    private static Map<String, Object> buildToolContext(RetrievalContext ctx,
                                                       @Nullable String approvedToolCallId) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        // tenantId 由 Controller fail-closed 守卫保证非空；userId 防御性条件写入
        // （ChatClient 断言 toolContext 无 null 值）
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
