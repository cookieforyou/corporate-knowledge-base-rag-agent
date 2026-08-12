package com.enterprise.kb.ai.metrics;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 业务指标统一注册中心（设计文档 13.3，任务 3.13）
 *
 * <p>双层可观测分工（13 章定案）：Langfuse 管 LLM trace 树 / prompt / 评估；
 * 本组件 + Prometheus + Grafana 管 QPS、延迟分位、错误率与**业务指标**。
 * 全项目业务指标在此集中注册（3.7/3.8 的 token 计数随之收编），避免散点
 * MeterRegistry 注入造成命名漂移。
 *
 * <p><b>指标清单与挂点</b>：
 * <ul>
 *   <li>{@code rag.feedback.like / rag.feedback.dislike}——反馈计数，
 *       3.17 反馈 API 落地时经 {@link #recordFeedback(boolean)} 接线（先注册待接）</li>
 *   <li>{@code rag.retrieval.total / rag.retrieval.hit}——检索执行/命中计数，
 *       AuditTraceAdvisor 按 final trace 是否非空计命中（内层被拒未达检索不计入
 *       分母）；命中率 = Prometheus 侧 hit/total 相除</li>
 *   <li>{@code rag.retrieval.latency}——混合检索耗时 Timer（p50/p95/p99），
 *       HybridDocumentRetriever 记录真实耗时</li>
 *   <li>{@code rag.tool.call.total / success / pending}——工具调用计数，
 *       AuditTraceAdvisor 按 ToolCall.status 分桶（EXECUTED 计成功，
 *       PENDING_APPROVAL 计挂起待审）</li>
 *   <li>{@code rag.token.total / rag.token.budget.rejected}——Token 消耗与预算
 *       拒绝（原 TokenBudgetAdvisor 分散注册，收编至此统一管理）</li>
 *   <li>{@code rag.guardrail.injection.blocked / pii.masked / output.replaced /
 *       rate.limited / token.budget}——护栏命中计数（簇⑤ B2，S3），按事件类型
 *       分列注册（与 tool.call 分桶同形态）；注入/限流/预算拒绝同时经
 *       AuditTraceAdvisor 落 kb_audit_log REJECTED 行，指标供 Prometheus 告警</li>
 * </ul>
 *
 * <p><b>标签纪律</b>：全部指标不带租户标签（防指标基数膨胀，3.8 定案延续）；
 * 租户级观测走 Redis 账本键与 kb_audit_log。
 *
 * <p>设计稿 13.3 其余指标暂不注册：{@code rag.retrieval.cache.hit}（语义缓存
 * 未实现，Phase 5.6）、{@code rag.llm.*}（模型层调用计数随 Phase 4 可观测增强）。
 */
@Component
public class AiBusinessMetrics {

    private final Counter feedbackLike;
    private final Counter feedbackDislike;
    private final Counter retrievalTotal;
    private final Counter retrievalHit;
    private final Timer retrievalLatency;
    private final Counter toolCallTotal;
    private final Counter toolCallSuccess;
    private final Counter toolCallPending;
    private final Counter tokenTotal;
    private final Counter tokenBudgetRejected;
    private final Counter routingChitchat;
    private final Counter routingKnowledge;
    private final Counter guardrailInjectionBlocked;
    private final Counter guardrailPiiMasked;
    private final Counter guardrailOutputReplaced;
    private final Counter guardrailRateLimited;
    private final Counter guardrailTokenBudget;

    public AiBusinessMetrics(MeterRegistry registry) {
        this.feedbackLike = Counter.builder("rag.feedback.like")
            .description("用户点赞反馈数（3.17 反馈 API 接线点）").register(registry);
        this.feedbackDislike = Counter.builder("rag.feedback.dislike")
            .description("用户点踩反馈数（3.17 反馈 API 接线点）").register(registry);
        this.retrievalTotal = Counter.builder("rag.retrieval.total")
            .description("混合检索执行次数").register(registry);
        this.retrievalHit = Counter.builder("rag.retrieval.hit")
            .description("检索命中次数（final 序列非空）").register(registry);
        this.retrievalLatency = Timer.builder("rag.retrieval.latency")
            .description("混合检索耗时").register(registry);
        this.toolCallTotal = Counter.builder("rag.tool.call.total")
            .description("工具调用次数").register(registry);
        this.toolCallSuccess = Counter.builder("rag.tool.call.success")
            .description("工具调用成功次数（EXECUTED）").register(registry);
        this.toolCallPending = Counter.builder("rag.tool.call.pending")
            .description("工具调用 HITL 挂起次数（PENDING_APPROVAL）").register(registry);
        this.tokenTotal = Counter.builder("rag.token.total")
            .description("AI Token 总消耗").register(registry);
        this.tokenBudgetRejected = Counter.builder("rag.token.budget.rejected")
            .description("Token 预算拒绝次数").register(registry);
        this.routingChitchat = Counter.builder("rag.routing.chitchat")
            .description("意图分类为闲聊/元问题，旁路检索直答（5.4 收窄版）").register(registry);
        this.routingKnowledge = Counter.builder("rag.routing.knowledge")
            .description("意图分类为知识问答，走完整检索链路（5.4 收窄版）").register(registry);
        this.guardrailInjectionBlocked = Counter.builder("rag.guardrail.injection.blocked")
            .description("Prompt 注入拦截次数（InputSanitizeAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailPiiMasked = Counter.builder("rag.guardrail.pii.masked")
            .description("PII 掩码触发次数（InputSanitizeAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailOutputReplaced = Counter.builder("rag.guardrail.output.replaced")
            .description("输出黑名单整段替换次数（OutputGuardrailAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailRateLimited = Counter.builder("rag.guardrail.rate.limited")
            .description("租户限流拒绝次数（RateLimitAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailTokenBudget = Counter.builder("rag.guardrail.token.budget")
            .description("Token 预算拒绝次数——安全域视图（成本域同事件见 rag.token.budget.rejected，簇⑤ B2 S3）").register(registry);
    }

    /** 用户反馈计数（3.17 反馈 API 接线点） */
    public void recordFeedback(boolean positive) {
        (positive ? feedbackLike : feedbackDislike).increment();
    }

    /** 检索执行 + 命中计数（hit = final 重排序列非空） */
    public void recordRetrieval(boolean hit) {
        retrievalTotal.increment();
        if (hit) {
            retrievalHit.increment();
        }
    }

    /** 混合检索耗时（HybridDocumentRetriever 真实耗时） */
    public void recordRetrievalLatency(Duration elapsed) {
        retrievalLatency.record(elapsed);
    }

    /** 工具调用计数：按状态分桶（EXECUTED 成功 / PENDING_APPROVAL 挂起待审） */
    public void recordToolCall(String status) {
        toolCallTotal.increment();
        if (RetrievalContext.ToolCall.STATUS_EXECUTED.equals(status)) {
            toolCallSuccess.increment();
        } else if (RetrievalContext.ToolCall.STATUS_PENDING_APPROVAL.equals(status)) {
            toolCallPending.increment();
        }
    }

    /** Token 消耗累加（TokenBudgetAdvisor after 回写） */
    public void addTokens(long tokens) {
        tokenTotal.increment(tokens);
    }

    /** Token 预算拒绝计数（TokenBudgetAdvisor before 超额）——成本域与安全域双计数 */
    public void recordTokenBudgetRejected() {
        tokenBudgetRejected.increment();
        guardrailTokenBudget.increment();
    }

    /** 意图路由分流计数（5.4 收窄版）：闲聊/元问题旁路检索。分流比 = chitchat/(chitchat+knowledge) */
    public void recordRoutingChitchat() {
        routingChitchat.increment();
    }

    /** 意图路由分流计数（5.4 收窄版）：知识问答走完整检索（含 fail-open 回落） */
    public void recordRoutingKnowledge() {
        routingKnowledge.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：Prompt 注入拦截（InputSanitizeAdvisor 抛 PROMPT_INJECTION 前） */
    public void recordInjectionBlocked() {
        guardrailInjectionBlocked.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：PII 掩码触发（InputSanitizeAdvisor，非拒绝型干预） */
    public void recordPiiMasked() {
        guardrailPiiMasked.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：输出黑名单整段替换（OutputGuardrailAdvisor，非拒绝型干预） */
    public void recordOutputReplaced() {
        guardrailOutputReplaced.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：租户限流拒绝（RateLimitAdvisor 抛 RATE_LIMITED 前） */
    public void recordRateLimited() {
        guardrailRateLimited.increment();
    }
}
