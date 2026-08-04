package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.ChatService;
import com.enterprise.kb.api.dto.AgentStreamEvent.ChunkTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.ErrorEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.SourceTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.TokenEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.TraceEvent;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 对话 Controller — 同步 + SSE 流式（命名事件，设计文档 11.3）
 *
 * <p>检索上下文（租户隔离 + 溯源）采用**每请求实例 + Advisor 参数传递**：
 * Controller 在请求线程创建 {@link RetrievalContext} 并以 JwtUtils 填充身份，
 * 经 ChatClient advisor 参数随 Query.context 流入检索组件——同步/流式/虚拟线程
 * 全程线程模型无关（2026-08-02 重构：取代请求作用域代理，其于 MVC 异步请求完结后
 * 不可解析，曾致流式路径租户过滤与 trace 静默失效、SSE 尾帧崩溃）。
 *
 * <p>事件协议（兼容 Phase 1）：TOKEN/ERROR/DONE 为无名事件且数据形状不变；
 * TRACE 为新增命名事件（流末推送完整溯源），旧前端自动忽略。
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

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final JwtUtils jwtUtils;

    /**
     * 同步 RAG 问答（多轮：请求带 sessionId，响应回传）
     *
     * <pre>
     * POST /api/v1/chat
     * { "query": "什么是增值税发票？", "sessionId": "可选" }
     * → { "code": 200, "data": { "answer": "...", "sessionId": "..." } }
     * </pre>
     */
    @PostMapping("/chat")
    public ApiResponse<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        String sessionId = resolveSessionId(body);
        // 日志/归档走脱敏形态（Advisor 链只保护模型上下文与 Redis 记忆，
        // PG 归档与访问日志须在入口同规则脱敏）；注入判定仍由 Advisor 链对原文执行
        String safeQuery = InputSanitizeAdvisor.sanitize(query);
        log.info("用户 [{}] 发起问答: sessionId={}, query={}",
            jwtUtils.getCurrentUsername(), sessionId, safeQuery);
        RetrievalContext ctx = newRetrievalContext();
        String answer = chatService.chat(query, sessionId, ctx);
        chatSessionService.archiveTurn(sessionId, ctx.getTenantId(), ctx.getUserId(), safeQuery, answer);
        return ApiResponse.success(Map.of("answer", answer, "sessionId", sessionId));
    }

    /**
     * 流式 RAG 问答（SSE）：TOKEN*（无名）→ TRACE（命名，溯源）→ [DONE]（无名）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        String sessionId = resolveSessionId(body);
        // 日志/归档走脱敏形态（同同步路径）；注入判定仍由 Advisor 链对原文执行
        String safeQuery = InputSanitizeAdvisor.sanitize(query);
        log.info("用户 [{}] 发起流式问答: sessionId={}, query={}",
            jwtUtils.getCurrentUsername(), sessionId, safeQuery);
        // 请求线程创建并填充检索上下文：纯实例经 advisor 参数传递，流末直接读取同一实例
        RetrievalContext traceCtx = newRetrievalContext();
        // 累积流式 token 为完整回答（归档用；旁路数据，不影响帧转发）
        StringBuilder answerBuffer = new StringBuilder();

        return chatService.chatStream(query, sessionId, traceCtx)
            .filter(token -> token != null && !token.isEmpty())
            .doOnNext(answerBuffer::append)
            .map(token -> ServerSentEvent.<Object>builder(new TokenEvent(token)).build())
            .concatWith(Mono.fromSupplier(() -> ServerSentEvent.<Object>builder(safeBuildTrace(traceCtx))
                .event("TRACE").build()))
            .concatWith(Mono.just(ServerSentEvent.<Object>builder("[DONE]").build()))
            .doOnComplete(() -> chatSessionService.archiveTurn(
                sessionId, traceCtx.getTenantId(), traceCtx.getUserId(),
                safeQuery, answerBuffer.toString()))
            .onErrorResume(e -> {
                log.error("流式问答失败", e);
                return Flux.just(ServerSentEvent.<Object>builder(
                    new ErrorEvent(String.valueOf(e.getMessage()))).build());
            });
    }

    /** 会话 ID：请求携带则复用（多轮），缺省生成一次性 ID（兼容 Phase 1 单轮前端） */
    private static String resolveSessionId(Map<String, String> body) {
        String sessionId = body.get("sessionId");
        return sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
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

    private static final List<String> SCORE_KEYS =
        List.of("bm25_score", "bm25_rank", "vector_rank", "fusion_score", "rerank_score", "rerank_rank");

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
            asString(meta.get("file_name")),
            meta.get("page_num") instanceof Number n ? n.intValue() : null,
            scores,
            snippet);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
