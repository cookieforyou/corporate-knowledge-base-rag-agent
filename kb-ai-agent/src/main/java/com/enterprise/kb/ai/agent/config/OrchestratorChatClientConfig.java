package com.enterprise.kb.ai.agent.config;

import com.enterprise.kb.ai.advisor.AuditTraceAdvisor;
import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.advisor.OutputGuardrailAdvisor;
import com.enterprise.kb.ai.advisor.RateLimitAdvisor;
import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.ai.advisor.TokenBudgetAdvisor;
import com.enterprise.kb.ai.agent.orchestration.KnowledgeSearchTools;
import com.enterprise.kb.ai.agent.orchestration.SubAgentClientFactory;
import com.enterprise.kb.ai.agent.orchestration.SubAgentRegistry;
import com.enterprise.kb.ai.agent.orchestration.SubAgentSpec;
import com.enterprise.kb.ai.agent.orchestration.TaskTool;
import com.enterprise.kb.ai.agent.service.AgentOrchestratorService;
import com.enterprise.kb.ai.agent.tool.EnterpriseMockReadTools;
import com.enterprise.kb.ai.guardrail.PromptCanary;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.prompt.PromptTemplates;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 编排链装配（簇⑤ 5.3，设计文档 §11.5.5）——Orchestrator-Workers 收窄骨架
 *
 * <p><b>开关纪律（D3 定案）</b>：{@code rag.orchestrator.enabled} 缺省 false——
 * 关闭态本配置类整体缺位（编排族 Bean 全缺），rag/tool 两链逐字节零变化
 * （簇③④ 条件装配同款）；mode=agent 由 AgentController 显式 400
 * ORCHESTRATOR_DISABLED，不静默回落。
 *
 * <p><b>链序</b>：与 tool 链同构（Audit(10) → TokenBudget(30) → RateLimit(100) →
 * OutputGuardrail(110) → InputSanitize(300) → SemanticInjection(320) → Memory(400) →
 * ToolCallingAdvisor(1000 复用既有 agentToolCallingAdvisor Bean)），差异仅两处：
 * ① defaultTools = task 单工具（主 Agent 上下文零叶子工具 schema）；
 * ② system prompt = 编排者角色 + 子代理清单注入（renderRoster 渲染）。
 *
 * <p><b>主 Agent 记忆</b>：挂 Memory(400)——多轮编排会话（同 sessionId 跨 mode
 * 互通）；子代理不挂记忆（TaskTool 委派即隔离上下文，Part 4 语义）。
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.orchestrator", name = "enabled", havingValue = "true")
public class OrchestratorChatClientConfig {

    /** 数据查询子代理 system prompt（契约位：真实 OA/ERP 工具替换时按真实系统能力修订） */
    static final String DATA_QUERY_SYSTEM_PROMPT =
        "你是企业业务数据查询子代理。依据任务描述调用数据查询工具获取员工信息、"
            + "假期余额等业务数据，如实返回查询结果；查询不到时明确说明，不得编造。";

    /** 报告生成子代理 system prompt（纯 LLM 写作，无工具） */
    static final String REPORT_WRITER_SYSTEM_PROMPT =
        "你是企业报告撰写子代理。依据任务描述中提供的资料与要求，撰写结构清晰、"
            + "表述准确的中文报告或文稿；资料未覆盖的信息如实标注待补充，不得编造。";

    /**
     * 子代理执行线程池：虚拟线程 per-task（对齐 hybridRetrievalExecutor 先例）——
     * 委派为阻塞式 LLM 调用，有界超时经 {@link TaskTool} 的 Future.get 控制。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService orchestratorSubAgentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 知识检索子代理 system prompt（检索管线零 LLM 主答，工具调用与综合在子代理轻模型上） */
    static final String KNOWLEDGE_SEARCHER_SYSTEM_PROMPT =
        "你是企业知识检索子代理。依据任务描述先调用 searchKnowledge 检索知识库获取证据，"
            + "需要完整上下文时再调用 getDocument 读取全文；基于检索结果如实归纳回答，"
            + "引用处标注文件名与页码，检索不到时明确说明，不得编造。";

    /**
     * 子代理注册表（批2 全量三 Spec，D2 定案差异化模型分工）：
     * 知识检索/数据查询挂 fallbackChatModel（qwen3.8-flash，轻任务挂备先例 v2.83），
     * 报告生成挂 smartRoutingChatModel（主答质量）。
     */
    @Bean
    public SubAgentRegistry subAgentRegistry(KnowledgeSearchTools knowledgeSearchTools,
                                             EnterpriseMockReadTools enterpriseMockReadTools,
                                             @Qualifier("fallbackChatModel") ChatModel fallbackChatModel,
                                             @Qualifier("smartRoutingChatModel") ChatModel smartRoutingChatModel,
                                             @Value("${rag.orchestrator.subagent-timeout-seconds:60}")
                                             int timeoutSeconds) {
        return new SubAgentRegistry(List.of(
            new SubAgentSpec("knowledge-searcher",
                "检索企业知识库（制度、规范、流程、事实依据），检索与全文读取",
                KNOWLEDGE_SEARCHER_SYSTEM_PROMPT,
                List.of(knowledgeSearchTools),
                fallbackChatModel,
                timeoutSeconds),
            new SubAgentSpec("data-query",
                "查询企业业务数据（员工信息、部门、职位、年假余额等）",
                DATA_QUERY_SYSTEM_PROMPT,
                List.of(enterpriseMockReadTools),
                fallbackChatModel,
                timeoutSeconds),
            new SubAgentSpec("report-writer",
                "依据给定资料撰写报告、文稿与情况说明（纯写作，无数据查询能力）",
                REPORT_WRITER_SYSTEM_PROMPT,
                List.of(),
                smartRoutingChatModel,
                timeoutSeconds)));
    }

    /**
     * Task 委派工具 + 子客户端工厂：按 Spec.name 缓存（工具集静态，实例可复用）；
     * 轻链构建——不挂 Memory/Audit/配额（主请求已计账，防双计），身份经
     * TaskTool 下传子调用 toolContext。
     */
    @Bean
    public TaskTool taskTool(SubAgentRegistry subAgentRegistry,
                             ObservationRegistry observationRegistry,
                             // 容器内 ExecutorService Bean 不唯一（另有 hybridRetrievalExecutor）——
                             // 显式限定防按类型歧义（坑位㊺，IDEA 编译无 -parameters 时按名消歧亦失效）
                             @Qualifier("orchestratorSubAgentExecutor") ExecutorService orchestratorSubAgentExecutor,
                             AiBusinessMetrics aiBusinessMetrics) {
        ConcurrentHashMap<String, ChatClient> clientCache = new ConcurrentHashMap<>();
        SubAgentClientFactory factory = spec -> clientCache.computeIfAbsent(spec.name(), name -> {
            ChatClient.Builder builder =
                ChatClient.builder(spec.chatModel(), observationRegistry, null, null)
                    .defaultSystem(spec.systemPrompt());
            if (!spec.toolObjects().isEmpty()) {
                builder.defaultTools(spec.toolObjects().toArray());
            }
            return builder.build();
        });
        return new TaskTool(subAgentRegistry, factory, orchestratorSubAgentExecutor, aiBusinessMetrics);
    }

    @Bean
    public AgentOrchestratorService agentOrchestratorService(
            @Qualifier("orchestratorChatClient") ChatClient orchestratorChatClient) {
        return new AgentOrchestratorService(orchestratorChatClient);
    }

    @Bean
    public ChatClient orchestratorChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                             ObservationRegistry observationRegistry,
                                             ChatMemory agentChatMemory,
                                             AuditTraceAdvisor auditTraceAdvisor,
                                             TokenBudgetAdvisor tokenBudgetAdvisor,
                                             RateLimitAdvisor rateLimitAdvisor,
                                             OutputGuardrailAdvisor outputGuardrailAdvisor,
                                             InputSanitizeAdvisor inputSanitizeAdvisor,
                                             SemanticInjectionAdvisor semanticInjectionAdvisor,
                                             ToolCallingAdvisor agentToolCallingAdvisor,
                                             TaskTool taskTool,
                                             SubAgentRegistry subAgentRegistry,
                                             PromptCanary promptCanary) {
        // 同 tool 链：显式传 ObservationRegistry（簇① 单参 builder NOOP registry 坑）+
        // 金丝雀嵌入（安全簇① T5，输出回显经共享 OutputGuardrailAdvisor 后验拦截）
        return ChatClient.builder(chatModel, observationRegistry, null, null)
            .defaultSystem(promptCanary.embed(String.format(
                PromptTemplates.ORCHESTRATOR_SYSTEM_PROMPT, subAgentRegistry.renderRoster())))
            .defaultAdvisors(
                auditTraceAdvisor,
                tokenBudgetAdvisor,
                rateLimitAdvisor,
                outputGuardrailAdvisor,
                inputSanitizeAdvisor,
                semanticInjectionAdvisor,
                MessageChatMemoryAdvisor.builder(agentChatMemory).order(400).build(),
                agentToolCallingAdvisor)
            .defaultTools(taskTool)
            .build();
    }
}
