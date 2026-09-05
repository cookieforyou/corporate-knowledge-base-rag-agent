package com.enterprise.kb.ai.agent.orchestration;

import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task 委派工具（簇⑤ 5.3，Spring AI Agentic Patterns Part 4 形态）——编排链唯一工具
 *
 * <p>主 Agent 经本工具将子任务委派给专职子代理：子代理在隔离上下文执行
 * （独立 system prompt / 工具集 / 模型，不挂主会话记忆），结果以文本回流
 * 主 Agent 综合作答。委派即工具调用——SSE TOOL_CALL 事件 / 审计 tool_calls
 * 快照 / rag.tool.call 指标自然承载，协议零变更。
 *
 * <p><b>身份链（11.5 双链路纪律延伸）</b>：主请求身份（tenantId/userId/
 * RetrievalContext）经 toolContext 传入本工具，再下传子代理调用——子代理内
 * 检索/数据工具消费同一身份，租户隔离 fail-closed 语义全链不断；
 * {@code APPROVED_TOOL_CALL_ID} 不下传（编排链无 HITL，凭证不可跨越委派层）。
 *
 * <p><b>失败语义</b>：子代理失败/超时不抛异常击穿主链，一律以错误描述文本返回，
 * 由主 Agent 决策重试、换路或如实告知。超时用非打断式 {@code cancel(false)}
 * （坑位㊶ 先例：打断阻塞线程即杀弃底层 HTTP 连接，弃任务经客户端读超时
 * 自然收敛）。
 *
 * <p><b>委派预算硬闸（E2E 热修四）</b>：按 RetrievalContext 快照内 {@code task:*}
 * 记录数计已发起委派，达 {@code maxDelegations} 上限后直接文本拒绝——防主 Agent
 * 超时重试等行为形成无出口循环拖死请求（prompt 纪律是软约束，硬闸兜底）。
 */
public class TaskTool {

    private final SubAgentRegistry registry;
    private final SubAgentClientFactory clientFactory;
    private final ExecutorService executor;
    private final AiBusinessMetrics metrics;
    private final int maxDelegations;

    public TaskTool(SubAgentRegistry registry, SubAgentClientFactory clientFactory,
                    ExecutorService executor, AiBusinessMetrics metrics, int maxDelegations) {
        this.registry = registry;
        this.clientFactory = clientFactory;
        this.executor = executor;
        this.metrics = metrics;
        this.maxDelegations = Math.max(1, maxDelegations);
    }

    @Tool(description = "将子任务委派给专职子代理执行并返回其结果。子代理在隔离上下文中工作"
        + "（看不到主对话历史），拥有专属工具与系统指令；应通过本工具委派子任务，"
        + "并依据返回结果综合作答")
    public String task(
            @ToolParam(description = "子代理名称，必须取自系统提示中的可用子代理清单") String subagent,
            @ToolParam(description = "自包含的子任务描述：目标、约束与期望产出（子代理看不到主对话历史）") String description,
            ToolContext toolContext) {

        SubAgentSpec spec = registry.find(subagent == null ? "" : subagent.trim());
        if (spec == null) {
            return "⚠️ 未知的子代理: " + subagent + "（可用: " + registry.renderNames() + "）";
        }
        Map<String, Object> ctx = toolContext == null ? Map.of() : toolContext.getContext();
        RetrievalContext retrievalContext =
            ctx.get(ToolContextKeys.RETRIEVAL_CONTEXT) instanceof RetrievalContext rc ? rc : null;

        // 委派预算硬闸（E2E 热修四）：已达上限不再执行，文本要求主 Agent 立即综合作答
        if (retrievalContext != null && countDelegations(retrievalContext) >= maxDelegations) {
            recordToolCall(retrievalContext, spec.name(), RetrievalContext.ToolCall.STATUS_FAILED,
                "委派预算超限拒绝（已达 " + maxDelegations + " 次）: " + description);
            return "⚠️ 本次请求委派次数已达上限（" + maxDelegations + "），不要再委派，"
                + "请立即基于已获得的子代理结果综合作答，缺失部分如实说明。";
        }

        Future<String> future = executor.submit(() ->
            clientFactory.create(spec)
                .prompt()
                .user(description)
                .toolContext(buildSubAgentToolContext(ctx, retrievalContext))
                .call().content());
        long startNanos = System.nanoTime();
        try {
            String result = future.get(spec.timeoutSeconds(), TimeUnit.SECONDS);
            recordOutcome(spec.name(), true, startNanos);
            recordToolCall(retrievalContext, spec.name(), RetrievalContext.ToolCall.STATUS_EXECUTED,
                description);
            return result;
        } catch (TimeoutException e) {
            // 非打断式弃任务（坑位㊶）：底层调用经客户端读超时自然收敛
            future.cancel(false);
            recordOutcome(spec.name(), false, startNanos);
            recordToolCall(retrievalContext, spec.name(), RetrievalContext.ToolCall.STATUS_FAILED,
                "执行超时（>" + spec.timeoutSeconds() + "s）: " + description);
            return "⚠️ 子代理 " + spec.name() + " 执行超时（>" + spec.timeoutSeconds()
                + "s）。请换其他途径或如实告知用户该部分暂时无法完成。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordOutcome(spec.name(), false, startNanos);
            recordToolCall(retrievalContext, spec.name(), RetrievalContext.ToolCall.STATUS_FAILED,
                "执行被中断: " + description);
            return "⚠️ 子代理 " + spec.name() + " 执行被中断，请如实告知用户。";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            recordOutcome(spec.name(), false, startNanos);
            recordToolCall(retrievalContext, spec.name(), RetrievalContext.ToolCall.STATUS_FAILED,
                "执行失败: " + cause.getMessage());
            return "⚠️ 子代理 " + spec.name() + " 执行失败：" + cause.getMessage()
                + "。可重试一次或如实告知用户。";
        }
    }

    /** 快照内已发起的委派次数（task:* 记录，含成功/失败/超时全终态） */
    private static int countDelegations(RetrievalContext ctx) {
        return (int) ctx.getToolCalls().stream()
            .filter(tc -> tc.toolName() != null && tc.toolName().startsWith("task:"))
            .count();
    }

    /** 委派终态指标（rag.orchestrator.delegation / subagent.duration，簇⑤ 批2） */
    private void recordOutcome(String subAgentName, boolean success, long startNanos) {
        metrics.recordOrchestratorDelegation(subAgentName, success);
        metrics.recordOrchestratorSubAgentDuration(subAgentName,
            Duration.ofNanos(System.nanoTime() - startNanos));
    }

    /**
     * 子调用 toolContext：身份三键下传，HITL 凭证不下传（编排链无跨层审批，
     * 凭证不可经委派透传——真实写工具升级路径见 §11.5.5 契约）。
     * ChatClient 断言 toolContext 无 null 值——条件写入。
     */
    private static Map<String, Object> buildSubAgentToolContext(Map<String, Object> parentCtx,
                                                                RetrievalContext retrievalContext) {
        Map<String, Object> subContext = new HashMap<>();
        Object tenantId = parentCtx.get(ToolContextKeys.TENANT_ID);
        if (tenantId != null) {
            subContext.put(ToolContextKeys.TENANT_ID, tenantId);
        }
        Object userId = parentCtx.get(ToolContextKeys.USER_ID);
        if (userId != null) {
            subContext.put(ToolContextKeys.USER_ID, userId);
        }
        if (retrievalContext != null) {
            subContext.put(ToolContextKeys.RETRIEVAL_CONTEXT, retrievalContext);
        }
        return subContext;
    }

    private static void recordToolCall(RetrievalContext ctx, String subAgentName,
                                       String status, String summary) {
        if (ctx != null) {
            ctx.addToolCall(new RetrievalContext.ToolCall("task:" + subAgentName, status, null, summary));
        }
    }
}
