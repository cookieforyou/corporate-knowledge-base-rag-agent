package com.enterprise.kb.ai.metrics;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
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
 *       AuditTraceAdvisor 落 kb_audit_log REJECTED 行，指标供 Prometheus 告警。
 *       安全簇① T5 扩充：{@code output.replaced.{business_confidential /
 *       compliance_sensitive / competitor_comparison}} 三分类子项（switch 收口
 *       独立 Counter，零标签纪律）+ {@code output.canary} 系统提示金丝雀回显拦截</li>
 *   <li>{@code rag.document.reindex.started / succeeded / failed}——文档增量重入库
 *       计数（簇⑥ C1）：started 于 reparse/replace 占用成功计，succeeded/failed
 *       经 ETL 进度回调 COMPLETED/FAILED 终态计（异步管线的观测点在回调层）</li>
 *   <li>{@code rag.request.total / rejected / error}——双链问答请求结果计数
 *       （Phase 4 簇①）：AuditTraceAdvisor 遍历双链全量请求旁路计数；
 *       rejected = BusinessException（护栏/配额拒绝，审计 REJECTED），
 *       error = 其他异常（供应商/系统，审计 ERROR）；为告警规则提供
 *       拒绝率/错误率分母（4.2）</li>
 *   <li>{@code rag.rerank.total / rag.rerank.fallback}——rerank 执行/降级计数
 *       （Phase 4 簇①）：RerankDocumentPostProcessor 运行时调用计 total，
 *       解析失败/结构异常/调用失败降级 fusion_score 截断计 fallback；
 *       endpoint 未配置的静态降级不计入（配置态非运行态）</li>
 *   <li>{@code rag.ttft}——流式首 Token 延迟 Timer（p50/p95/p99，Phase 4 簇② 4.3）：
 *       AgentController 流式路径自请求进入至首个非空 token 送达的端到端时延，
 *       双链共记（无 mode 标签，延续零标签纪律）；同步路径无首 token 语义不记</li>
 *   <li>{@code rag.chunk.edit / soft.delete / restore}——Chunk 运维操作计数
 *       （Phase 4 簇③ 4.4）：kb-admin ChunkOpsService 成功路径计；操作类型经
 *       recordChunkOps(String) 收口为独立 Counter（不加 operation 标签）</li>
 *   <li>{@code rag.badcase.annotate / rag.badcase.reingest}——Bad Case 运营闭环
 *       计数（Phase 4 簇④ 4.7）：根因标注 / Golden Set 回灌成功路径计，
 *       recordBadCaseOps(String) 收口（同款零标签纪律）</li>
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
    private final Counter guardrailOutputReplacedBusinessConfidential;
    private final Counter guardrailOutputReplacedComplianceSensitive;
    private final Counter guardrailOutputReplacedCompetitorComparison;
    private final Counter guardrailOutputCanary;
    private final Counter guardrailRateLimited;
    private final Counter guardrailTokenBudget;
    private final Counter documentReindexStarted;
    private final Counter documentReindexSucceeded;
    private final Counter documentReindexFailed;
    private final Counter requestTotal;
    private final Counter requestRejected;
    private final Counter requestError;
    private final Counter rerankTotal;
    private final Counter rerankFallback;
    private final Timer ttft;
    private final Counter chunkEdit;
    private final Counter chunkSoftDelete;
    private final Counter chunkRestore;
    private final Counter badCaseAnnotate;
    private final Counter badCaseReingest;
    private final Counter mcpSearch;
    private final Counter mcpGetDocument;
    private final Counter mcpAsk;

    public AiBusinessMetrics(MeterRegistry registry) {
        this.feedbackLike = Counter.builder("rag.feedback.like")
            .description("用户点赞反馈数（3.17 反馈 API 接线点）").register(registry);
        this.feedbackDislike = Counter.builder("rag.feedback.dislike")
            .description("用户点踩反馈数（3.17 反馈 API 接线点）").register(registry);
        this.retrievalTotal = Counter.builder("rag.retrieval.total")
            .description("混合检索执行次数").register(registry);
        this.retrievalHit = Counter.builder("rag.retrieval.hit")
            .description("检索命中次数（final 序列非空）").register(registry);
        // v2.11 草图承诺 p50/p95/p99，实现期遗漏——簇② 4.3 面板消费分位时补齐
        this.retrievalLatency = Timer.builder("rag.retrieval.latency")
            .description("混合检索耗时")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
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
            .description("输出敏感词表整段替换次数（OutputGuardrailAdvisor，簇⑤ B2 S3）").register(registry);
        // 输出面分类化子项（安全簇① T5）：按 OutputFamily 三分类分列——零标签纪律下
        // 经 recordOutputReplaced(family) switch 收口为独立 Counter（不加 family 标签）
        this.guardrailOutputReplacedBusinessConfidential =
            Counter.builder("rag.guardrail.output.replaced.business_confidential")
                .description("输出替换次数——业务保密分类（安全簇① T5）").register(registry);
        this.guardrailOutputReplacedComplianceSensitive =
            Counter.builder("rag.guardrail.output.replaced.compliance_sensitive")
                .description("输出替换次数——合规敏感分类（安全簇① T5）").register(registry);
        this.guardrailOutputReplacedCompetitorComparison =
            Counter.builder("rag.guardrail.output.replaced.competitor_comparison")
                .description("输出替换次数——竞品对比分类（安全簇① T5）").register(registry);
        this.guardrailOutputCanary = Counter.builder("rag.guardrail.output.canary")
            .description("系统提示金丝雀回显拦截次数——确证提示泄露（安全簇① T5）").register(registry);
        this.guardrailRateLimited = Counter.builder("rag.guardrail.rate.limited")
            .description("租户限流拒绝次数（RateLimitAdvisor，簇⑤ B2 S3）").register(registry);
        this.guardrailTokenBudget = Counter.builder("rag.guardrail.token.budget")
            .description("Token 预算拒绝次数——安全域视图（成本域同事件见 rag.token.budget.rejected，簇⑤ B2 S3）").register(registry);
        this.documentReindexStarted = Counter.builder("rag.document.reindex.started")
            .description("文档增量重入库发起次数（reparse/replace 占用成功，簇⑥ C1）").register(registry);
        this.documentReindexSucceeded = Counter.builder("rag.document.reindex.succeeded")
            .description("文档增量重入库成功次数（ETL 进度回调 COMPLETED，簇⑥ C1）").register(registry);
        this.documentReindexFailed = Counter.builder("rag.document.reindex.failed")
            .description("文档增量重入库失败次数（ETL 进度回调 FAILED，簇⑥ C1）").register(registry);
        this.requestTotal = Counter.builder("rag.request.total")
            .description("双链问答请求总数（审计旁路计数，Phase 4 簇①）").register(registry);
        this.requestRejected = Counter.builder("rag.request.rejected")
            .description("护栏/配额拒绝请求数（BusinessException → 审计 REJECTED，Phase 4 簇①）").register(registry);
        this.requestError = Counter.builder("rag.request.error")
            .description("供应商/系统错误请求数（审计 ERROR，Phase 4 簇①）").register(registry);
        this.rerankTotal = Counter.builder("rag.rerank.total")
            .description("rerank 运行时执行次数（Phase 4 簇①）").register(registry);
        this.rerankFallback = Counter.builder("rag.rerank.fallback")
            .description("rerank 降级次数（解析失败/结构异常/调用失败 → fusion_score 截断，Phase 4 簇①）").register(registry);
        this.ttft = Timer.builder("rag.ttft")
            .description("流式首 Token 延迟（Phase 4 簇② 4.3）")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.chunkEdit = Counter.builder("rag.chunk.edit")
            .description("Chunk 运维编辑次数（Phase 4 簇③ 4.4）").register(registry);
        this.chunkSoftDelete = Counter.builder("rag.chunk.soft.delete")
            .description("Chunk 软删除次数（Phase 4 簇③ 4.4）").register(registry);
        this.chunkRestore = Counter.builder("rag.chunk.restore")
            .description("Chunk 软删恢复次数（Phase 4 簇③ 4.4）").register(registry);
        this.badCaseAnnotate = Counter.builder("rag.badcase.annotate")
            .description("Bad Case 根因标注次数（Phase 4 簇④ 4.7）").register(registry);
        this.badCaseReingest = Counter.builder("rag.badcase.reingest")
            .description("Bad Case Golden Set 回灌次数（Phase 4 簇④ 4.7）").register(registry);
        this.mcpSearch = Counter.builder("rag.mcp.search")
            .description("MCP search 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
        this.mcpGetDocument = Counter.builder("rag.mcp.get_document")
            .description("MCP get_document 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
        this.mcpAsk = Counter.builder("rag.mcp.ask")
            .description("MCP ask 工具调用次数（Phase 4 簇⑤ 4.10）").register(registry);
    }

    /** Chunk 运维操作计数（Phase 4 簇③ 4.4：edit / soft_delete / restore） */
    public void recordChunkOps(String operation) {
        switch (operation) {
            case "edit" -> chunkEdit.increment();
            case "soft_delete" -> chunkSoftDelete.increment();
            case "restore" -> chunkRestore.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** Bad Case 运营闭环计数（Phase 4 簇④ 4.7：annotate 根因标注 / reingest Golden 回灌） */
    public void recordBadCaseOps(String operation) {
        switch (operation) {
            case "annotate" -> badCaseAnnotate.increment();
            case "reingest" -> badCaseReingest.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** MCP 三件套工具调用计数（Phase 4 簇⑤ 4.10：search / get_document / ask，调用审计的指标面） */
    public void recordMcpToolCall(String operation) {
        switch (operation) {
            case "search" -> mcpSearch.increment();
            case "get_document" -> mcpGetDocument.increment();
            case "ask" -> mcpAsk.increment();
            default -> { /* 未知操作不计——零标签纪律下的键收口 */ }
        }
    }

    /** 流式首 Token 延迟（AgentController 流式路径：请求进入 → 首个非空 token，双链共记） */
    public void recordTtft(Duration elapsed) {
        ttft.record(elapsed);
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

    /**
     * 护栏命中计数（簇⑤ B2 S3 / 安全簇① T5）：输出敏感词表整段替换
     * （OutputGuardrailAdvisor，非拒绝型干预）——总项恒计，命中词项族系属
     * OutputFamily 三分类时另计对应分类子项（未知族系只计总项）。
     */
    public void recordOutputReplaced(String family) {
        guardrailOutputReplaced.increment();
        if (family == null) {
            return;
        }
        switch (family.trim().toUpperCase()) {
            case "BUSINESS_CONFIDENTIAL" -> guardrailOutputReplacedBusinessConfidential.increment();
            case "COMPLIANCE_SENSITIVE" -> guardrailOutputReplacedComplianceSensitive.increment();
            case "COMPETITOR_COMPARISON" -> guardrailOutputReplacedCompetitorComparison.increment();
            default -> { /* 未知/UNCLASSIFIED 族系只计总项——零标签纪律下的键收口 */ }
        }
    }

    /** 护栏命中计数（安全簇① T5）：系统提示金丝雀回显——确证提示泄露，整段替换 */
    public void recordOutputCanary() {
        guardrailOutputCanary.increment();
    }

    /** 护栏命中计数（簇⑤ B2 S3）：租户限流拒绝（RateLimitAdvisor 抛 RATE_LIMITED 前） */
    public void recordRateLimited() {
        guardrailRateLimited.increment();
    }

    /** 文档增量重入库计数（簇⑥ C1）：started 占用成功 / succeeded/failed 终态回调 */
    public void recordReindexStarted() {
        documentReindexStarted.increment();
    }

    /** 文档增量重入库计数（簇⑥ C1） */
    public void recordReindexOutcome(boolean succeeded) {
        (succeeded ? documentReindexSucceeded : documentReindexFailed).increment();
    }

    /**
     * 请求结果计数（Phase 4 簇①，告警分母）：与审计三态同语义——
     * error=null 计 total；BusinessException（护栏/配额拒绝，审计 REJECTED）计 rejected；
     * 其他异常（供应商/系统，审计 ERROR）计 error。拒绝率/错误率 = 对应计数/total。
     */
    public void recordRequestOutcome(Throwable error) {
        requestTotal.increment();
        if (error instanceof BusinessException) {
            requestRejected.increment();
        } else if (error != null) {
            requestError.increment();
        }
    }

    /** rerank 执行/降级计数（Phase 4 簇①）：降级率 = fallback/total */
    public void recordRerank(boolean fallback) {
        rerankTotal.increment();
        if (fallback) {
            rerankFallback.increment();
        }
    }
}
