package com.enterprise.kb.api.controller;

import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.ai.agent.service.AgentOrchestratorService;
import com.enterprise.kb.ai.agent.service.ToolChatService;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.dto.AgentStreamEvent.ChunkTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.DoneEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.ErrorEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.SourceTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.TokenEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.ToolCallEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.ToolCallInfo;
import com.enterprise.kb.api.dto.AgentStreamEvent.TraceEvent;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 对话 Controller — 同步 + SSE 流式（命名事件，设计文档 11.3）
 *
 * <p>检索上下文（租户隔离 + 溯源）采用**每请求实例 + Advisor 参数传递**：
 * Controller 在请求线程创建 {@link RetrievalContext} 并以 JwtUtils 填充身份，
 * 经 ChatClient advisor 参数随 Query.context 流入检索组件——同步/流式/虚拟线程
 * 全程线程模型无关（2026-08-02 重构：取代请求作用域代理，其于 MVC 异步请求完结后
 * 不可解析，曾致流式路径租户过滤与 trace 静默失效、SSE 尾帧崩溃）。
 *
 * <p>事件协议：TOKEN/ERROR/DONE 为无名事件；TRACE 为命名事件（流末推送完整溯源）。
 * DONE 帧自 3.17 起携带 JSON 载荷 {messageId, traceId}（原字面量 "[DONE]"，
 * 反馈闭环的定位句柄，协议修订见 AgentStreamEvent 类注与 11.3 v2.14）。
 *
 * <p><b>会话协议（3.1）</b>：请求体可选 {@code sessionId} 标识多轮会话——
 * 前端生成并全程复用同一 ID 即获得多轮记忆；缺省时后端生成一次性 ID
 * （等价 Phase 1 单轮行为）。同步响应回传 sessionId；流式协议不变
 * （sessionId 由前端自备）。对话完成后异步归档 kb_session/kb_message。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    /** 问答模式（11.5 双链路 + 簇⑤ 5.3 第三链）：rag=知识库检索问答，tool=工具事务，agent=多子代理编排 */
    private static final String MODE_RAG = "rag";
    private static final String MODE_TOOL = "tool";
    private static final String MODE_AGENT = "agent";

    private final RagChatService ragChatService;
    private final ToolChatService toolChatService;
    /** 编排服务 ObjectProvider 容忍缺位（簇⑤ D3：enabled=false 时 Bean 缺位 → 显式 400） */
    private final ObjectProvider<AgentOrchestratorService> orchestratorServiceProvider;
    private final ChatSessionService chatSessionService;
    private final JwtUtils jwtUtils;
    private final ObservationRegistry observationRegistry;
    private final AiBusinessMetrics aiBusinessMetrics;
    /** PII 识别器注册表（安全簇③ C2）：日志/归档入口脱敏与对话链同源 */
    private final PiiRecognizerRegistry piiRecognizerRegistry;

    /**
     * 同步问答（多轮：请求带 sessionId，响应回传）
     *
     * <pre>
     * POST /api/v1/chat
     * { "query": "...", "sessionId": "可选", "mode": "rag|tool|agent（可选，缺省 rag）",
     *   "approvedToolCallId": "可选（tool 模式 HITL 确认回传）" }
     * → { "code": 200, "data": { "answer": "...", "sessionId": "...", "toolCalls": [...] } }
     * </pre>
     *
     * <p><b>三链分流（11.5 双链路 + 簇⑤ 5.3）</b>：mode=rag 走 ragAgentChatClient
     * （纯检索、零工具，toolCalls 恒空）；mode=tool 走 toolAgentChatClient（纯工具
     * 事务、零检索）；mode=agent 走 orchestratorChatClient（多子代理编排，主 Agent
     * 仅持 task 委派工具，委派记录同经 toolCalls 透出）。toolCalls 为工具调用记录
     * （3.4）：写工具挂起时含 status=PENDING_APPROVAL 与 approvalId，前端确认后
     * 携带 approvedToolCallId 发起二次请求触发真正执行（agent 链无 HITL 语义）。
     */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        String sessionId = resolveSessionId(body);
        String mode = resolveMode(body);
        String approvedToolCallId = body.get("approvedToolCallId");
        // 日志/归档走脱敏形态（Advisor 链只保护模型上下文与 Redis 记忆，
        // PG 归档与访问日志须在入口同规则脱敏）；注入判定仍由 Advisor 链对原文执行
        String safeQuery = piiRecognizerRegistry.mask(query);
        log.info("用户 [{}] 发起问答: mode={}, sessionId={}, query={}",
            jwtUtils.getCurrentUsername(), mode, sessionId, safeQuery);
        RetrievalContext ctx = newRetrievalContext();
        // 历史会话真续聊（v2.17）：Redis 记忆过期（TTL 24h）时以 PG 消息回填窗口，
        // 必须发生在 Advisor 链执行前（fail-open，异常不影响本轮）
        chatSessionService.reseedMemoryIfAbsent(sessionId);
        // 本轮句柄（3.17）：messageId 定位归档消息（反馈外键），traceId 关联审计行（反馈回填）
        String assistantMessageId = UUID.randomUUID().toString();
        ctx.setTraceId(UUID.randomUUID().toString());

        boolean toolMode = MODE_TOOL.equals(mode);
        boolean agentMode = MODE_AGENT.equals(mode);
        if (!toolMode) {
            warnIfStrayApprovalId(approvedToolCallId);
        }
        AgentOrchestratorService orchestrator = agentMode ? requireOrchestrator() : null;
        String answer = toolMode
                ? toolChatService.chatTool(query, sessionId, ctx, approvedToolCallId)
                : agentMode
                    ? orchestrator.chatOrchestrator(query, sessionId, ctx)
                    : ragChatService.chatRag(query, sessionId, ctx);

        chatSessionService.archiveTurn(sessionId, ctx.getTenantId(), ctx.getUserId(),
            safeQuery, answer, assistantMessageId,
            // 溯源载荷（v2.17）：tool/agent 链零检索、闲聊免检索直答无溯源 → null
            toolMode || agentMode || ctx.isSkipRetrieval() ? null : safeBuildTrace(ctx), ctx.getTraceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("answer", answer);
        data.put("sessionId", sessionId);
        data.put("messageId", assistantMessageId);
        data.put("traceId", ctx.getTraceId());
        data.put("toolCalls", ctx.getToolCalls());
        return ApiResponse.success(data);
    }

    /**
     * 流式 RAG 问答（SSE）：TOKEN*（无名）→ TRACE（命名，溯源，仅检索路径）
     * → DONE（无名，JSON 载荷 {messageId, traceId}，3.17 反馈定位句柄）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        String sessionId = resolveSessionId(body);
        String mode = resolveMode(body);
        String approvedToolCallId = body.get("approvedToolCallId");
        // 日志/归档走脱敏形态（同同步路径）；注入判定仍由 Advisor 链对原文执行
        String safeQuery = piiRecognizerRegistry.mask(query);
        log.info("用户 [{}] 发起流式问答: mode={}, sessionId={}, query={}",
            jwtUtils.getCurrentUsername(), mode, sessionId, safeQuery);
        // 请求线程创建并填充检索上下文：纯实例经 advisor 参数传递，流末直接读取同一实例
        RetrievalContext traceCtx = newRetrievalContext();
        // 历史会话真续聊（v2.17）：同同步路径，Advisor 链执行前回填过期记忆（fail-open）
        chatSessionService.reseedMemoryIfAbsent(sessionId);
        // 本轮句柄（3.17）：messageId 经 DONE 帧送达前端作反馈定位键，归档复用同一 ID；
        // traceId 经 RetrievalContext 透传 AuditTraceAdvisor 落库，反馈回填凭此关联审计行
        String assistantMessageId = UUID.randomUUID().toString();
        traceCtx.setTraceId(UUID.randomUUID().toString());
        // 累积流式 token 为完整回答（归档用；旁路数据，不影响帧转发）
        StringBuilder answerBuffer = new StringBuilder();
        // TTFT 计量（rag.ttft，簇② 4.3）：请求进入 → 首个非空 token，双链共记；
        // 计时起点取 Controller 入口（含 Advisor 链前置开销，端到端口径）
        long requestStartNanos = System.nanoTime();
        AtomicBoolean ttftRecorded = new AtomicBoolean();

        boolean toolMode = MODE_TOOL.equals(mode);
        boolean agentMode = MODE_AGENT.equals(mode);
        if (!toolMode) {
            warnIfStrayApprovalId(approvedToolCallId);
        }
        // 开关守卫在请求线程执行（Flux 组装前）：关闭态与 INVALID_MODE 同形态 400，不进流
        AgentOrchestratorService orchestrator = agentMode ? requireOrchestrator() : null;
        Flux<String> tokens = toolMode
            ? toolChatService.chatStreamTool(query, sessionId, traceCtx, approvedToolCallId)
            : agentMode
                ? orchestrator.chatStreamOrchestrator(query, sessionId, traceCtx)
                : ragChatService.chatStreamRag(query, sessionId, traceCtx);

        Flux<ServerSentEvent<Object>> sseFlux = tokens
            .filter(token -> token != null && !token.isEmpty())
            .doOnNext(token -> {
                if (ttftRecorded.compareAndSet(false, true)) {
                    aiBusinessMetrics.recordTtft(Duration.ofNanos(System.nanoTime() - requestStartNanos));
                }
            })
            .doOnNext(answerBuffer::append)
            .map(token -> ServerSentEvent.<Object>builder(new TokenEvent(token)).build());
        // SSE 事件按链路精简（11.5）：tool/agent 链只可能产生 TOOL_CALL（无溯源数据不推空
        // TRACE）——agent 链的委派即工具调用（task），协议零变更（簇⑤ 5.3）；
        // rag 链只推 TRACE（零工具不产生 TOOL_CALL）；闲聊免检索直答路径（5.4 收窄版）
        // 无溯源数据，对齐「不推空帧」纪律同样省略 TRACE
        if (toolMode || agentMode) {
            sseFlux = sseFlux.concatWith(Flux.defer(() -> traceCtx.getToolCalls().isEmpty()
                ? Flux.empty()
                : Flux.just(ServerSentEvent.<Object>builder(toToolCallEvent(traceCtx))
                    .event("TOOL_CALL").build())));
        } else {
            sseFlux = sseFlux.concatWith(Mono.defer(() -> traceCtx.isSkipRetrieval()
                ? Mono.empty()
                : Mono.fromSupplier(() ->
                    ServerSentEvent.<Object>builder(safeBuildTrace(traceCtx)).event("TRACE").build())));
        }
        Flux<ServerSentEvent<Object>> result = sseFlux
            .concatWith(Mono.just(ServerSentEvent.<Object>builder(
                new DoneEvent(assistantMessageId, traceCtx.getTraceId())).build()))
            .doOnComplete(() -> chatSessionService.archiveTurn(
                sessionId, traceCtx.getTenantId(), traceCtx.getUserId(),
                safeQuery, answerBuffer.toString(), assistantMessageId,
                toolMode || agentMode || traceCtx.isSkipRetrieval() ? null : safeBuildTrace(traceCtx),
                traceCtx.getTraceId()))
            .onErrorResume(e -> {
                log.error("流式问答失败", e);
                return Flux.just(ServerSentEvent.<Object>builder(
                    new ErrorEvent(String.valueOf(e.getMessage()))).build());
            });
        return bridgeTraceContext(result);
    }

    /**
     * 请求线程观测桥入 Reactor Context（Phase 4 簇① trace 碎片化修复）。
     *
     * <p>Spring AI 2.0 流式链不依赖 ThreadLocal 上下文自动恢复——chat_client / Advisor
     * 观测在 {@code Flux.deferContextual} 内**显式**经 {@link ObservationThreadLocalAccessor#KEY}
     * （{@code micrometer.observation}）从 ContextView 读取父观测（DefaultChatClient 源码契约，
     * 注释明示 "without relying on automatic context propagation"）。请求线程上 server
     * observation 作用域必然开启（ServerHttpObservationFilter 作用域内），此处显式捕获写入，
     * 直击 Spring AI 读取契约——不依赖 MVC 快照回写 / Reactor 钩子的隐式捕获，确定性兜底
     * （BaseAdvisor.adviseStream 会将下游链 publishOn 至 boundedElastic，ThreadLocal
     * 作用域不再可靠）。无当前观测（如评估宿主）原样返回。
     */
    private Flux<ServerSentEvent<Object>> bridgeTraceContext(Flux<ServerSentEvent<Object>> flux) {
        Observation currentObservation = observationRegistry.getCurrentObservation();
        if (currentObservation == null) {
            return flux;
        }
        return flux.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, currentObservation));
    }

    /** TOOL_CALL 载荷投影（RetrievalContext.ToolCall → SSE DTO） */
    private static ToolCallEvent toToolCallEvent(RetrievalContext ctx) {
        return new ToolCallEvent(ctx.getToolCalls().stream()
            .map(tc -> new ToolCallInfo(tc.toolName(), tc.status(), tc.approvalId(), tc.summary()))
            .toList());
    }

    /** 会话 ID：请求携带则复用（多轮），缺省生成一次性 ID（兼容 Phase 1 单轮前端） */
    private static String resolveSessionId(Map<String, String> body) {
        String sessionId = body.get("sessionId");
        return sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
    }

    /**
     * 问答模式解析（11.5 双链路 + 簇⑤ 5.3 第三链）：缺省 rag 兼容现状；
     * 大小写归一；非法值 400 INVALID_MODE（协议层错误在请求处理期拒绝，
     * 不进 SSE 流）。mode=agent 的可用性守卫见 {@link #requireOrchestrator()}。
     * 跨链自动意图路由不实现（5.4-A 归档，真实工具立项触发复活）。
     */
    private String resolveMode(Map<String, String> body) {
        String mode = body.get("mode");
        mode = mode == null || mode.isBlank() ? MODE_RAG : mode.trim().toLowerCase();
        if (!MODE_RAG.equals(mode) && !MODE_TOOL.equals(mode) && !MODE_AGENT.equals(mode)) {
            throw new BusinessException("INVALID_MODE", "不支持的问答模式: " + mode + "（仅支持 rag|tool|agent）");
        }
        return mode;
    }

    /**
     * 编排链可用性守卫（簇⑤ D3）：rag.orchestrator.enabled=false 时编排族 Bean
     * 缺位——mode=agent 显式拒绝（ORCHESTRATOR_DISABLED 400），不静默回落 tool
     * 改变语义。ObjectProvider 容忍缺位（簇③ CacheCheckAdvisor 同款先例）。
     */
    private AgentOrchestratorService requireOrchestrator() {
        AgentOrchestratorService service = orchestratorServiceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException("ORCHESTRATOR_DISABLED",
                "编排模式未启用（rag.orchestrator.enabled=false），无法以 agent 模式问答");
        }
        return service;
    }

    /** rag 模式收到 HITL 凭证：rag 链无工具消费方，忽略并告警（调用方协议误用提示） */
    private static void warnIfStrayApprovalId(String approvedToolCallId) {
        if (approvedToolCallId != null && !approvedToolCallId.isBlank()) {
            log.warn("rag 模式收到 approvedToolCallId，已忽略（HITL 凭证仅 tool 模式有效）");
        }
    }

    // ── 检索上下文与溯源投影 ──

    /**
     * 请求线程上创建检索上下文并填充身份（JWT owner→tenantId、sub→userId）
     *
     * <p><b>身份完整性守卫（3.9+3.10 安全收敛，fail-closed）</b>：HTTP 层
     * SecurityConfig 只保证「已认证」，不保证 token 携带租户身份（owner claim
     * 可能缺失）。tenantId 缺失意味着检索层无法做租户过滤——直接拒绝，
     * 绝不允许以无过滤形态进入检索链路（跨租户全量可见）。检索层另有
     * 同语义防御纵深（HybridDocumentRetriever）。
     */
    private RetrievalContext newRetrievalContext() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(jwtUtils.getCurrentTenantId());
        ctx.setUserId(jwtUtils.getCurrentUserId());
        if (ctx.getTenantId() == null || ctx.getTenantId().isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return ctx;
    }

    /** TRACE 载荷构建（容错）：溯源是旁路增值数据，异常降级为空溯源，不击穿 SSE 流 */
    private static TraceEvent safeBuildTrace(RetrievalContext ctx) {
        try {
            return buildTraceEvent(ctx);
        } catch (Exception e) {
            log.warn("TRACE 溯源构建失败，已降级为空溯源", e);
            return new TraceEvent(List.of());
        }
    }

    private static TraceEvent buildTraceEvent(RetrievalContext ctx) {
        return new TraceEvent(ctx.getTraceSummary().stream()
            .map(entry -> new SourceTrace(entry.source(), entry.documents().stream()
                .map(doc -> toChunkTrace(doc, entry.source()))
                .toList(), entry.latencyMs()))
            .toList());
    }

    /** 簇④：追加 graph 路得分/排名键（图路缺位时元数据无键，帧形态不变） */
    private static final List<String> SCORE_KEYS =
        List.of("bm25_score", "bm25_rank", "vector_rank", "graph_score", "graph_rank",
            "fusion_score", "rerank_score", "rerank_rank");

    /** Chunk 轻量投影（不序列化全文，控制 SSE 帧体积） */
    private static ChunkTrace toChunkTrace(Document doc, String source) {
        Map<String, Object> meta = doc.getMetadata();
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String key : SCORE_KEYS) {
            Object value = meta.get(key);
            if (value != null) {
                scores.put(key, value);
            }
        }
        // 向量路原始相似度（簇 C 观察补全：该路元数据无独立得分键）
        if ("vector".equals(source) && doc.getScore() != null) {
            scores.put("similarity", doc.getScore());
        }
        String text = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ");
        String snippet = text.length() <= 120 ? text : text.substring(0, 120) + "…";
        return new ChunkTrace(
            asString(meta.get("chunk_id")),
            asString(meta.get("doc_id")),
            asString(meta.get("file_name")),
            meta.get("page_num") instanceof Number n ? n.intValue() : null,
            scores,
            snippet);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
