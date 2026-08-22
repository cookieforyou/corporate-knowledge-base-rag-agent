package com.enterprise.kb.ai.agent.config;

import com.enterprise.kb.ai.advisor.AuditTraceAdvisor;
import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.advisor.OutputGuardrailAdvisor;
import com.enterprise.kb.ai.advisor.RateLimitAdvisor;
import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.ai.advisor.TokenBudgetAdvisor;
import com.enterprise.kb.ai.agent.tool.EnterpriseMockTools;
import com.enterprise.kb.ai.guardrail.PromptCanary;
import com.enterprise.kb.ai.prompt.PromptTemplates;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具事务链装配（设计文档 11.5，任务 3.19 双链路拆分）
 *
 * <p><b>模块定位</b>：kb-ai-agent 承载 Agent 事务域（工具编排 + HITL + 未来
 * MCP 5.11 / Multi-Agent 5.3）；本链与 kb-ai-core 的 ragAgentChatClient 物理隔离——
 * 工具请求零检索消耗（不挂 RetrievalTrace/RetrievalAugmentation），RAG 请求零工具
 * schema 干扰（rag 链不挂 defaultTools）。
 *
 * <p><b>共享基座（均来自 kb-ai-core）</b>：smartRoutingChatModel（主备容灾）、
 * agentChatMemory（同 sessionId 跨链历史互通）、护栏/配额 Advisor（安全与成本
 * 管控不分流）。
 *
 * <p><b>链序</b>：Audit(10) → TokenBudget(30) → RateLimit(100) → OutputGuardrail(110) →
 * InputSanitize(300) → SemanticInjection(320，安全簇⑤ E1) → Memory(400) →
 * ToolCallingAdvisor(1000)。工具循环只包裹
 * 模型调用（order 1000 最内层，护栏/记忆每请求仅执行一次）；审计居最外层，
 * 被拒请求同样落 kb_audit_log（11.6）。
 */
@Configuration
public class ToolAgentChatClientConfig {

    /**
     * 工具调用 Advisor（order 1000 自建，自动注册让位）——源起 3.3，随双链路
     * 拆分迁入本模块。详见原 AgentChatClientConfig 注记：自动注册默认序
     * HIGHEST_PRECEDENCE+300（链最外层）致工具循环重复穿越内层 Advisor。
     */
    @Bean
    public ToolCallingAdvisor agentToolCallingAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .advisorOrder(1000)
            .build();
    }

    @Bean
    public ChatClient toolAgentChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                          ObservationRegistry observationRegistry,
                                          ChatMemory agentChatMemory,
                                          AuditTraceAdvisor auditTraceAdvisor,
                                          TokenBudgetAdvisor tokenBudgetAdvisor,
                                          RateLimitAdvisor rateLimitAdvisor,
                                          OutputGuardrailAdvisor outputGuardrailAdvisor,
                                          InputSanitizeAdvisor inputSanitizeAdvisor,
                                          SemanticInjectionAdvisor semanticInjectionAdvisor,
                                          ToolCallingAdvisor agentToolCallingAdvisor,
                                          EnterpriseMockTools enterpriseMockTools,
                                          PromptCanary promptCanary) {
        // 同 ragAgentChatClient：单参 builder 默认 NOOP registry，chat_client/Advisor
        // 观测静默缺失——显式传入应用 ObservationRegistry（簇① 碎片化定案）
        // 系统提示金丝雀（安全簇① T5）：与 rag 链同一 PromptCanary Bean，
        // 输出回显由共享 OutputGuardrailAdvisor 聚合后验拦截
        return ChatClient.builder(chatModel, observationRegistry, null, null)
            .defaultSystem(promptCanary.embed(PromptTemplates.TOOL_SYSTEM_PROMPT))
            .defaultAdvisors(
                auditTraceAdvisor,
                tokenBudgetAdvisor,
                rateLimitAdvisor,
                outputGuardrailAdvisor,
                inputSanitizeAdvisor,
                semanticInjectionAdvisor,
                MessageChatMemoryAdvisor.builder(agentChatMemory).order(400).build(),
                agentToolCallingAdvisor)
            .defaultTools(enterpriseMockTools)
            .build();
    }
}
