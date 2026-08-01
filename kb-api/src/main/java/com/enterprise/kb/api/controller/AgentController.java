package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.ChatService;
import com.enterprise.kb.api.dto.AgentStreamEvent;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话 Controller — 同步 + SSE 流式（命名事件，设计文档 11.3）
 *
 * <p>2.12 起流式链路返回 {@code Flux<ServerSentEvent>}：由 MVC 在**请求线程**订阅，
 * Advisor 链（RetrievalTraceAdvisor 填充请求级上下文）因此天然持有请求作用域与
 * SecurityContext——这是 Phase 1 SseEmitter+线程池形态做不到的（跨线程后作用域丢失）。
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
    private final ObjectProvider<RetrievalContext> retrievalContextProvider;

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
        String answer = chatService.chat(query);
        return ApiResponse.success(Map.of("answer", answer));
    }

    /**
     * 流式 RAG 问答（SSE）：TOKEN*（无名）→ TRACE（命名，溯源）→ [DONE]（无名）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatStream(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        log.info("用户 [{}] 发起流式问答: {}", jwtUtils.getCurrentUsername(), query);
        // 请求线程捕获请求级 trace 上下文：Reactor 流跨线程后无法再取请求作用域 Bean（11.3 v2 注）
        RetrievalContext traceCtx = currentTraceContext();

        return chatService.chatStream(query)
            .filter(token -> token != null && !token.isEmpty())
            .map(token -> ServerSentEvent.<Object>builder(new TokenEvent(token)).build())
            .concatWith(Mono.fromSupplier(() -> ServerSentEvent.<Object>builder(buildTraceEvent(traceCtx))
                .event("TRACE").build()))
            .concatWith(Mono.just(ServerSentEvent.<Object>builder("[DONE]").build()))
            .onErrorResume(e -> {
                log.error("流式问答失败: {}", e.getMessage());
                return Flux.just(ServerSentEvent.<Object>builder(
                    new ErrorEvent(String.valueOf(e.getMessage()))).build());
            });
    }

    // ── 溯源投影（检索组件记录的元数据 → 前端可消费的轻量结构）──

    private RetrievalContext currentTraceContext() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        try {
            return retrievalContextProvider.getObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static TraceEvent buildTraceEvent(RetrievalContext ctx) {
        if (ctx == null) {
            return new TraceEvent(List.of());
        }
        return new TraceEvent(ctx.getTraceSummary().stream()
            .map(entry -> new SourceTrace(entry.source(), entry.documents().stream()
                .map(AgentController::toChunkTrace)
                .toList()))
            .toList());
    }

    private static final List<String> SCORE_KEYS =
        List.of("bm25_score", "bm25_rank", "vector_rank", "fusion_score", "rerank_score", "rerank_rank");

    private static ChunkTrace toChunkTrace(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        Map<String, Object> scores = new LinkedHashMap<>();
        for (String key : SCORE_KEYS) {
            Object value = meta.get(key);
            if (value != null) {
                scores.put(key, value);
            }
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
