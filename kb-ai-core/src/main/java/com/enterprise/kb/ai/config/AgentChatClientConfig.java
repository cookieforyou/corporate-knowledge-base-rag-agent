package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.advisor.OutputGuardrailAdvisor;
import com.enterprise.kb.ai.advisor.RateLimitAdvisor;
import com.enterprise.kb.ai.advisor.RetrievalTraceAdvisor;
import com.enterprise.kb.ai.advisor.TokenBudgetAdvisor;
import com.enterprise.kb.ai.memory.FaultTolerantChatMemory;
import com.enterprise.kb.ai.tool.EnterpriseMockTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 对话 ChatClient 装配（设计文档 11.2，任务 3.1）
 *
 * <p><b>Bean 拆分决策（3.1 复审定稿）</b>：记忆 Advisor 不挂共享 {@code chatClient}
 * Bean，而是装配独立的 {@code agentChatClient}。原因（2.0 GA 字节码实证）：
 * {@code BaseChatMemoryAdvisor.getConversationId()} 对缺失的 CONVERSATION_ID
 * 参数是 Assert 硬断言而非静默跳过——kb-eval 注入 {@code chatClient} 且不传
 * 会话 ID，挂上记忆 Advisor 评估链路会整体抛错。拆分后：
 * <ul>
 *   <li>{@code chatClient}（ChatConfig）：纯 RAG 链路，kb-eval 继续度量
 *       检索/生成本征质量，Phase 2 基线持续有效</li>
 *   <li>{@code agentChatClient}（本配置）：记忆 + 检索的生产对话链路，
 *       Phase 3 后续 Advisor（护栏/限流/审计/工具）按 11.2 链序增量挂载</li>
 * </ul>
 *
 * <p>Advisor 链序（11.2 v2 表增量挂载）：TokenBudget(30) → RateLimit(100) →
 * OutputGuardrail(110) → InputSanitize(300) → Memory(400) → Trace(450) →
 * Retrieval(500)。记忆先于检索注入历史消息；配额类护栏居最前，被拒请求
 * 零下游消耗。
 *
 * <p>会话记忆形态（v2.3 实证修正，见 11 章）：2.0 GA 无 RedisTemplate 构造器，
 * Redis 仓储经 {@code spring-ai-starter-model-chat-memory-repository-redis}
 * 自动配置（Jedis 形态，前缀 {@code spring.ai.chat.memory.redis}）。此处显式
 * 构建 {@link MessageWindowChatMemory}（滑动窗口）并包以
 * {@link FaultTolerantChatMemory}——自动配置的默认 ChatMemory Bean 随之让位
 * （@ConditionalOnMissingBean）。
 */
@Configuration
public class AgentChatClientConfig {

    /**
     * 会话记忆：Redis 仓储 + 滑动窗口 + 容错装饰。
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
     * 生产 Agent 对话链路（预算 + 限流 + 护栏 + 多轮记忆 + 溯源 + 混合检索 RAG）。
     * CONVERSATION_ID 与 RetrievalContext 均由 Controller 经 advisor 参数传入
     * （参数链机制，不用 @RequestScope/ThreadLocal）。
     *
     * <p>Advisor 链序（11.2 v2 表）：TokenBudget(30) → RateLimit(100) →
     * OutputGuardrail(110，after 拦截) → InputSanitize(300，先于记忆防 PII 落库) →
     * Memory(400) → Trace(450) → Retrieval(500)。order 由各 Advisor getOrder()
     * 决定，此处列表顺序不敏感。预算/限流先于护栏与记忆：被拒请求不消耗
     * 下游任何资源（v2 链序重排原则）。
     */
    /**
     * 工具调用 Advisor（3.3，设计 11.2 链序表 order 1000 位）。
     *
     * <p><b>自建而非自动注册（源码级核验定稿）</b>：2.0 GA 的 ChatClient 检测到
     * 工具回调会自动挂 ToolCallingAdvisor，但其 DEFAULT_ORDER =
     * HIGHEST_PRECEDENCE+300（链最外层）——工具循环每轮会重新穿越全部内层
     * Advisor（限流/预算配额按迭代消耗、记忆/检索重复执行）。本表设计位 1000
     * （最内层）：工具循环只包裹模型调用，护栏/记忆/检索每请求仅执行一次。
     * 自建 ToolAdvisor 实例后自动注册让位（autoRegisterToolCallingAdvisor
     * 检测到已有 ToolAdvisor 即跳过，源码核验）。
     */
    @Bean
    public ToolCallingAdvisor agentToolCallingAdvisor(ToolCallingManager toolCallingManager) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .advisorOrder(1000)
            .build();
    }

    @Bean
    public ChatClient agentChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                      ChatMemory agentChatMemory,
                                      TokenBudgetAdvisor tokenBudgetAdvisor,
                                      RateLimitAdvisor rateLimitAdvisor,
                                      OutputGuardrailAdvisor outputGuardrailAdvisor,
                                      InputSanitizeAdvisor inputSanitizeAdvisor,
                                      RetrievalTraceAdvisor retrievalTraceAdvisor,
                                      RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
                                      ToolCallingAdvisor agentToolCallingAdvisor,
                                      EnterpriseMockTools enterpriseMockTools) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(
                tokenBudgetAdvisor,
                rateLimitAdvisor,
                outputGuardrailAdvisor,
                inputSanitizeAdvisor,
                MessageChatMemoryAdvisor.builder(agentChatMemory).order(400).build(),
                retrievalTraceAdvisor,
                retrievalAugmentationAdvisor,
                agentToolCallingAdvisor)
            .defaultTools(enterpriseMockTools)
            .build();
    }
}
