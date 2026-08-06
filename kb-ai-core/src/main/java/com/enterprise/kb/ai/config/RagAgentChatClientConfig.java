package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.advisor.AuditTraceAdvisor;
import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.advisor.OutputGuardrailAdvisor;
import com.enterprise.kb.ai.advisor.RateLimitAdvisor;
import com.enterprise.kb.ai.advisor.RetrievalTraceAdvisor;
import com.enterprise.kb.ai.advisor.TokenBudgetAdvisor;
import com.enterprise.kb.ai.memory.FaultTolerantChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 对话链装配（设计文档 11.2/11.5，任务 3.19 双链路拆分）
 *
 * <p><b>双链路定稿（2026-08-05 用户拍板）</b>：原 {@code agentChatClient} 单链
 * 揉合 RAG 检索与工具调用——工具请求白耗混合检索+重排、RAG 请求平白携带工具
 * schema、E2E 实证确认轮被检索上下文带偏。拆分为：
 * <ul>
 *   <li><b>ragAgentChatClient</b>（本配置，kb-ai-core）：纯检索问答，零工具</li>
 *   <li><b>toolAgentChatClient</b>（kb-ai-agent ToolAgentChatClientConfig）：
 *       纯工具事务，零检索</li>
 * </ul>
 * 两链共享 smartRoutingChatModel（主备容灾）、agentChatMemory（同 sessionId
 * 跨链历史互通）、护栏/配额 Advisor（安全与成本管控不分流）；Controller 经
 * 请求体 {@code mode: rag|tool} 显式分流（缺省 rag，自动意图路由预留 Phase 5.4）。
 *
 * <p><b>Bean 拆分决策（3.1 复审定稿，延续）</b>：记忆 Advisor 不挂共享
 * {@code chatClient}（kb-eval 评估用纯 RAG 链，缺失 CONVERSATION_ID 为 Assert
 * 硬断言）；生产链独立装配。
 *
 * <p>会话记忆形态（v2.3 实证修正，见 11 章）：2.0 GA 无 RedisTemplate 构造器，
 * Redis 仓储经 {@code spring-ai-starter-model-chat-memory-repository-redis}
 * 自动配置（Jedis 形态，前缀 {@code spring.ai.chat.memory.redis}）。此处显式
 * 构建 {@link MessageWindowChatMemory}（滑动窗口）并包以
 * {@link FaultTolerantChatMemory}——自动配置的默认 ChatMemory Bean 随之让位
 * （@ConditionalOnMissingBean）。
 */
@Configuration
public class RagAgentChatClientConfig {

    /**
     * 会话记忆：Redis 仓储 + 滑动窗口 + 容错装饰。**两链共享本 Bean**（同
     * sessionId 跨链历史互通）。
     *
     * <p>maxMessages 默认 20（≈10 轮）：对齐 Phase 3 验收项「多轮对话（10轮）
     * 上下文连贯性 > 90%」，窗口再大对 TTFT 与 token 成本不友好。
     */
    @Bean
    public ChatMemory agentChatMemory(
            ChatMemoryRepository chatMemoryRepository,
            @Value("${rag.chat.memory.max-messages:20}") int maxMessages) {
        return new FaultTolerantChatMemory(
            MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(maxMessages)
                .build());
    }

    /**
     * 纯 RAG 生产对话链（护栏 + 多轮记忆 + 溯源 + 混合检索 RAG，**零工具**）。
     * CONVERSATION_ID 与 RetrievalContext 由 Controller 经 advisor 参数传入。
     *
     * <p>链序（11.2 v2 表去掉工具位）：Audit(10) → TokenBudget(30) → RateLimit(100) →
     * OutputGuardrail(110) → InputSanitize(300) → Memory(400) → Trace(450) →
     * Retrieval(500)。order 由各 Advisor getOrder() 决定，列表顺序不敏感。
     * 审计居最外层：被拒/被限流请求同样落 kb_audit_log（11.6）。
     */
    @Bean
    public ChatClient ragAgentChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                         ChatMemory agentChatMemory,
                                         AuditTraceAdvisor auditTraceAdvisor,
                                         TokenBudgetAdvisor tokenBudgetAdvisor,
                                         RateLimitAdvisor rateLimitAdvisor,
                                         OutputGuardrailAdvisor outputGuardrailAdvisor,
                                         InputSanitizeAdvisor inputSanitizeAdvisor,
                                         RetrievalTraceAdvisor retrievalTraceAdvisor,
                                         RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是企业知识库 RAG Agent 助手。基于知识库检索到的内容回答问题。")
            .defaultAdvisors(
                auditTraceAdvisor,
                tokenBudgetAdvisor,
                rateLimitAdvisor,
                outputGuardrailAdvisor,
                inputSanitizeAdvisor,
                MessageChatMemoryAdvisor.builder(agentChatMemory).order(400).build(),
                retrievalTraceAdvisor,
                retrievalAugmentationAdvisor)
            .build();
    }
}
