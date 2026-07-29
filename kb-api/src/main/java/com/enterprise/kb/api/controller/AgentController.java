package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.service.ChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Agent 对话 Controller — 同步 + SSE 流式
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AgentController {

    private final ChatService chatService;
    private final JwtUtils jwtUtils;
    private final Executor etlExecutor;
    private static final ObjectMapper mapper = new ObjectMapper();

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
     * 流式 RAG 问答（SSE）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        etlExecutor.execute(() -> {
            try {
                chatService.chatStream(query)
                    .doOnComplete(() -> sendEvent(emitter, "[DONE]", true))
                    .doOnError(e -> sendEvent(emitter, Map.of("error", e.getMessage()), true))
                    .subscribe(token -> sendEvent(emitter, Map.of("token", token), false));
            } catch (Exception e) {
                sendEvent(emitter, Map.of("error", e.getMessage()), true);
            }
        });

        return emitter;
    }

    @SneakyThrows
    private void sendEvent(SseEmitter emitter, Object data, boolean complete) {
        emitter.send(SseEmitter.event().data(mapper.writeValueAsString(data)));
        if (complete) emitter.complete();
    }
}
