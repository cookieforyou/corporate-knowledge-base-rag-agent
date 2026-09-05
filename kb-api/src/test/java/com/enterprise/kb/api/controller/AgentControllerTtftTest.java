package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.service.AgentOrchestratorService;
import com.enterprise.kb.ai.agent.service.ToolChatService;
import org.springframework.beans.factory.ObjectProvider;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 流式 TTFT 计量测试（Phase 4 簇② 4.3）：首 Token 记且仅记一次；空流不记
 */
class AgentControllerTtftTest {

    private final RagChatService ragChatService = mock(RagChatService.class);
    private final ToolChatService toolChatService = mock(ToolChatService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentOrchestratorService> orchestratorProvider =
        mock(ObjectProvider.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(ragChatService, toolChatService, orchestratorProvider, chatSessionService, jwtUtils,
            ObservationRegistry.create(), new AiBusinessMetrics(meterRegistry), PiiRecognizerRegistry.defaults());
        when(jwtUtils.getCurrentUsername()).thenReturn("user_test");
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
    }

    @Test
    void streamRecordsTtftExactlyOnce() {
        when(ragChatService.chatStreamRag(anyString(), anyString(), any()))
            .thenReturn(Flux.just("hello", " world", "!"));

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "你好")).collectList().block();

        assertThat(events).hasSize(5); // 3 TOKEN + TRACE（mock ctx 非闲聊）+ DONE
        Timer ttft = meterRegistry.find("rag.ttft").timer();
        assertThat(ttft).isNotNull();
        assertThat(ttft.count()).isEqualTo(1);
        assertThat(ttft.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    void emptyTokensDoNotRecordTtft() {
        // filter 剥离 null/空 token 后无帧——TTFT 不记（无首 token 语义）
        when(ragChatService.chatStreamRag(anyString(), anyString(), any()))
            .thenReturn(Flux.just("", ""));

        List<ServerSentEvent<Object>> events = controller
            .chatStream(Map.of("query", "你好")).collectList().block();
        assertThat(events).hasSize(2); // TRACE + DONE（无 TOKEN 帧）

        Timer ttft = meterRegistry.find("rag.ttft").timer();
        assertThat(ttft == null || ttft.count() == 0).isTrue();
    }
}
