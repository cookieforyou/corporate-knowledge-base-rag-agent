package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.service.AgentOrchestratorService;
import com.enterprise.kb.ai.agent.service.ToolChatService;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 进度旁路合流测试（簇⑥ 体验批3）：ctx 参数链 listener → Sinks → merge——
 * PROGRESS 阶段帧与 TOOL_CALL 实时快照帧在 token 流期间下发（先于 TRACE/DONE）；
 * 流末 TOOL_CALL 投影保留兜底。
 */
class AgentControllerProgressTest {

    private final RagChatService ragChatService = mock(RagChatService.class);
    private final ToolChatService toolChatService = mock(ToolChatService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentOrchestratorService> orchestratorProvider =
        mock(ObjectProvider.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(ragChatService, toolChatService, orchestratorProvider,
            chatSessionService, jwtUtils, ObservationRegistry.create(),
            new AiBusinessMetrics(new SimpleMeterRegistry()), PiiRecognizerRegistry.defaults());
        when(jwtUtils.getCurrentUsername()).thenReturn("user_test");
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
    }

    @Test
    void progressEventFlowsAsProgressFrameBeforeTraceAndDone() {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any())).thenAnswer(inv -> {
            RetrievalContext ctx = inv.getArgument(2);
            ctx.emitProgress("stage", "意图识别与查询改写完成，检索知识库…");
            return Flux.just("回答");
        });

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "问题")).collectList().block();

        // mock 的 emit 发生在组装期（先于 merge 订阅）被 sink 缓冲、TOKEN 先出——
        // 断言 PROGRESS 在场且先于 TRACE（真实生产 emit 在流订阅后的工具/检索期）
        List<String> names = events.stream().map(e -> e.event() == null ? "TOKEN" : e.event()).toList();
        assertThat(names).contains("PROGRESS");
        assertThat(names.indexOf("PROGRESS")).isLessThan(names.indexOf("TRACE"));
        assertThat(events.get(names.indexOf("PROGRESS")).data().toString()).contains("意图识别");
    }

    @Test
    void toolCallSnapshotFlowsAsRealTimeToolCallFrame() {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any())).thenAnswer(inv -> {
            RetrievalContext ctx = inv.getArgument(2);
            ctx.addToolCall(new RetrievalContext.ToolCall("task:knowledge-searcher",
                RetrievalContext.ToolCall.STATUS_RUNNING, null, "检索 DDD"));
            ctx.emitToolCallsSnapshot();
            return Flux.just("回答");
        });

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "问题", "mode", "rag")).collectList().block();

        // 实时 TOOL_CALL 帧（rag 链流末投影走 TRACE 无 TOOL_CALL 兜底——快照帧恰 1 次）
        List<String> names = events.stream().map(e -> e.event() == null ? "TOKEN" : e.event()).toList();
        assertThat(names).contains("TOOL_CALL");
        assertThat(events.get(names.indexOf("TOOL_CALL")).data().toString()).contains("RUNNING");
    }
}
