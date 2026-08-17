package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.service.ToolChatService;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import io.micrometer.observation.Observation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流式 trace 合树桥接测试（Phase 4 簇① 碎片化修复）——钉死请求线程当前观测经
 * {@code micrometer.observation} 键写入 Reactor Context。该键是 Spring AI 2.0 流式链
 * 读取父观测的显式契约（DefaultChatClient#doGetObservableFluxChatResponse 经
 * deferContextual 读 ContextView，不走 ThreadLocal 自动恢复）。
 */
class AgentControllerStreamTraceContextTest {

    private final RagChatService ragChatService = mock(RagChatService.class);
    private final ToolChatService toolChatService = mock(ToolChatService.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final ObservationRegistry observationRegistry = ObservationRegistry.create();
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(ragChatService, toolChatService, chatSessionService, jwtUtils,
            observationRegistry, new AiBusinessMetrics(new SimpleMeterRegistry()), PiiRecognizerRegistry.defaults());
        when(jwtUtils.getCurrentUsername()).thenReturn("user_test");
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
    }

    /** 模拟 Spring AI 读取侧：源 Flux 的 deferContextual 读父观测 */
    private void stubStreamCapturingContext(AtomicReference<Object> seen) {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any())).thenAnswer(inv ->
            Flux.deferContextual(ctx -> {
                seen.set(ctx.getOrDefault(ObservationThreadLocalAccessor.KEY, null));
                return Flux.just("tok");
            }));
    }

    @Test
    void streamContextCarriesRequestThreadObservation() {
        AtomicReference<Object> seen = new AtomicReference<>();
        stubStreamCapturingContext(seen);

        Observation serverObservation = Observation.start("http.server.requests", observationRegistry);
        try (Observation.Scope ignored = serverObservation.openScope()) {
            controller.chatStream(Map.of("query", "问题")).blockLast(Duration.ofSeconds(5));
        } finally {
            serverObservation.stop();
        }

        assertThat(seen.get()).isSameAs(serverObservation);
    }

    @Test
    void streamContextAbsentWhenNoObservationOpen() {
        AtomicReference<Object> seen = new AtomicReference<>();
        stubStreamCapturingContext(seen);

        controller.chatStream(Map.of("query", "问题")).blockLast(Duration.ofSeconds(5));

        assertThat(seen.get()).isNull();
    }
}
