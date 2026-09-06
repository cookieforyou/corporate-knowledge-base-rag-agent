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
 * SSE REPLACE 追回帧测试（v2.109）：OutputGuardrailAdvisor 增量放行形态命中截断时，
 * ctx 打标 → Controller 流末在 TRACE 之前下发 REPLACE 帧（话术载荷）；
 * 无标记时帧缺席（协议零噪声）。
 */
class AgentControllerReplaceFrameTest {

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
    void replacedStreamEmitsReplaceFrameBeforeTraceAndDone() {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any())).thenAnswer(inv -> {
            // 模拟 110 advisor 增量形态命中：已放行前缀流出后 ctx 打标
            RetrievalContext ctx = inv.getArgument(2);
            ctx.markOutputReplaced("抱歉，由于合规要求，无法提供该信息。");
            return Flux.just("已放行的", "前缀");
        });

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "问题")).collectList().block();

        // 2 TOKEN + REPLACE + TRACE + DONE；REPLACE 位于 TOKEN 之后、TRACE 之前
        assertThat(events).hasSize(5);
        assertThat(events.get(2).event()).isEqualTo("REPLACE");
        assertThat(events.get(2).data().toString()).contains("抱歉，由于合规要求");
        assertThat(events.get(3).event()).isEqualTo("TRACE");
        assertThat(events.get(4).data().toString()).contains("messageId");
        // 归档话术（answerBuffer 前缀被替换）
        org.mockito.Mockito.verify(chatSessionService).archiveTurn(anyString(), anyString(), anyString(),
            anyString(), org.mockito.ArgumentMatchers.eq("抱歉，由于合规要求，无法提供该信息。"),
            anyString(), any(), any(), any());
    }

    @Test
    void cleanStreamOmitsReplaceFrame() {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any()))
            .thenReturn(Flux.just("正常", "回答"));

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "问题")).collectList().block();

        assertThat(events).hasSize(4);   // 2 TOKEN + TRACE + DONE，无 REPLACE
        assertThat(events.stream().map(ServerSentEvent::event))
            .doesNotContain("REPLACE");
    }
}
