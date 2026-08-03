package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.ChatService;
import com.enterprise.kb.api.dto.AgentStreamEvent.ChunkTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.ErrorEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.SourceTrace;
import com.enterprise.kb.api.dto.AgentStreamEvent.TokenEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.TraceEvent;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.dto.ApiResponse;
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
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final ChatService chatService;
    private final JwtUtils jwtUtils;

    /**
     * 同步 RAG 问答
     *
     * <pre>
     * POST /api/v1/chat
     * { "query": "什么是增值税发票？" }
     * → { "code": 200, "data": { "answer": "..." } }
     * </pre>
     */
    @PostMapping("/chat")
    public ApiResponse<Map<String, String>> chat(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        log.info("用户 [{}] 发起问答: {}", jwtUtils.getCurrentUsername(), query);
        String answer = chatService.chat(query, newRetrievalContext());
        return ApiResponse.success(Map.of("answer", answer));
    }

    /**
     * 流式 RAG 问答（SSE）：TOKEN*（无名）→ TRACE（命名，溯源）→ [DONE]（无名）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        log.info("用户 [{}] 发起流式问答: {}", jwtUtils.getCurrentUsername(), query);
        // 请求线程创建并填充检索上下文：纯实例经 advisor 参数传递，流末直接读取同一实例
        RetrievalContext traceCtx = newRetrievalContext();

        return chatService.chatStream(query, traceCtx)
            .filter(token -> token != null && !token.isEmpty())
            .map(token -> ServerSentEvent.<Object>builder(new TokenEvent(token)).build())
            .concatWith(Mono.fromSupplier(() -> ServerSentEvent.<Object>builder(safeBuildTrace(traceCtx))
                .event("TRACE").build()))
            .concatWith(Mono.just(ServerSentEvent.<Object>builder("[DONE]").build()))
            .onErrorResume(e -> {
                log.error("流式问答失败", e);
                return Flux.just(ServerSentEvent.<Object>builder(
                    new ErrorEvent(String.valueOf(e.getMessage()))).build());
            });
    }

    // ── 检索上下文与溯源投影 ──

    /** 请求线程上创建检索上下文并填充身份（JWT owner→tenantId、sub→userId） */
    private RetrievalContext newRetrievalContext() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(jwtUtils.getCurrentTenantId());
        ctx.setUserId(jwtUtils.getCurrentUserId());
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
