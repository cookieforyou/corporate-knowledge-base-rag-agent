package com.enterprise.kb.ai.agent.service;

import com.enterprise.kb.ai.advisor.AuditTraceAdvisor;
import com.enterprise.kb.ai.agent.orchestration.TaskTool;
import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * 编排链对话服务（簇⑤ 5.3，设计文档 §11.5.5）——与 ToolChatService 契约同构
 *
 * <p>第三链 mode=agent：orchestratorChatClient 的 defaultTools 仅
 * {@link TaskTool} 一个委派工具（主 Agent 上下文零叶子工具 schema），
 * 子代理委派/执行/综合全部发生在工具循环内。签名无 approvedToolCallId——
 * 编排链无 HITL（跨层审批为真实工具升级路径，物理消除凭证流入）。
 *
 * <p>非 @Service 注解：经 OrchestratorChatClientConfig 条件装配
 * （{@code rag.orchestrator.enabled=true}），关闭态 Bean 缺位——
 * AgentController 以 ObjectProvider 容忍并显式 400（簇③ 先例）。
 */
public class AgentOrchestratorService {

    private final ChatClient orchestratorChatClient;

    /** 显式构造器注入：多 ChatClient Bean 必须 @Qualifier 限定（3.2 @Primary 歧义教训） */
    public AgentOrchestratorService(@Qualifier("orchestratorChatClient") ChatClient orchestratorChatClient) {
        this.orchestratorChatClient = orchestratorChatClient;
    }

    /** 同步编排问答（主 Agent 分解委派 + 综合作答） */
    public String chatOrchestrator(String query, String sessionId, RetrievalContext retrievalContext) {
        return orchestratorChatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext)
                .param(AuditTraceAdvisor.MODE_KEY, "agent"))
            .toolContext(buildToolContext(retrievalContext))
            .call().content();
    }

    /** 流式编排问答（委派过程经流末 TOOL_CALL 命名事件推送，同 tool 链协议） */
    public Flux<String> chatStreamOrchestrator(String query, String sessionId,
                                               RetrievalContext retrievalContext) {
        return orchestratorChatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext)
                .param(AuditTraceAdvisor.MODE_KEY, "agent"))
            .toolContext(buildToolContext(retrievalContext))
            .stream().content();
    }

    /**
     * toolContext 通道组装：身份三键（HITL 凭证键不存在——编排链无审批语义）。
     * ChatClient 断言 toolContext 无 null 值——userId 防御性条件写入
     * （tenantId 由 Controller fail-closed 守卫保证非空）。
     */
    private static Map<String, Object> buildToolContext(RetrievalContext ctx) {
        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        toolContext.put(ToolContextKeys.TENANT_ID, ctx.getTenantId());
        if (ctx.getUserId() != null) {
            toolContext.put(ToolContextKeys.USER_ID, ctx.getUserId());
        }
        return toolContext;
    }
}
