package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2aAgentService 测试（Phase5簇⑥ 批2）：编排序（计数→限流→审计→chatRag）、
 * 会话前缀（contextId 多轮记忆域）、限流上抛零触达、异常计数。
 */
class A2aAgentServiceTest {

    private RagChatService ragChatService;
    private A2aRateLimiter rateLimiter;
    private A2aAuditRecorder auditRecorder;
    private SimpleMeterRegistry meterRegistry;
    private A2aAgentService service;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        rateLimiter = mock(A2aRateLimiter.class);
        auditRecorder = mock(A2aAuditRecorder.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new A2aAgentService(ragChatService, rateLimiter, auditRecorder,
            new AiBusinessMetrics(meterRegistry));
    }

    private static RetrievalContext context() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ctx.setUserId("user-1");
        ctx.setTraceId("trace-1");
        return ctx;
    }

    @Test
    void happyPathDelegatesWithSessionPrefixAndMetrics() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("答案 [ref-1]");

        String answer = service.handle("问题", "ctx-1", context());

        assertThat(answer).isEqualTo("答案 [ref-1]");
        // 会话前缀：contextId 多轮记忆域（A2A client 携同一 contextId 续问即延续）
        verify(ragChatService).chatRag(anyString(), org.mockito.ArgumentMatchers.eq("a2a-ctx-1"), any());
        verify(auditRecorder).record(anyString(), any(RetrievalContext.class));
        assertThat(meterRegistry.counter("rag.a2a.request").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.a2a.error").count()).isZero();
        assertThat(meterRegistry.timer("rag.a2a.chat.duration").count()).isEqualTo(1L);
    }

    @Test
    void rateLimitedPropagatesWithoutChat() {
        org.mockito.Mockito.doThrow(new BusinessException(Constants.ErrorCodes.RATE_LIMITED, "限流"))
            .when(rateLimiter).acquire("tenant-a");

        assertThatThrownBy(() -> service.handle("问题", "ctx-2", context()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(Constants.ErrorCodes.RATE_LIMITED);

        // 限流即闸：审计与对话零触达；rate-limited 计数在 limiter 内（此处 mock 不计）
        verify(auditRecorder, never()).record(anyString(), any());
        verify(ragChatService, never()).chatRag(anyString(), anyString(), any());
        assertThat(meterRegistry.counter("rag.a2a.error").count()).isZero();
    }

    @Test
    void chatFailureCountsErrorAndRethrows() {
        when(ragChatService.chatRag(anyString(), anyString(), any()))
            .thenThrow(new RuntimeException("模型不可用"));

        assertThatThrownBy(() -> service.handle("问题", "ctx-3", context()))
            .isInstanceOf(RuntimeException.class);

        assertThat(meterRegistry.counter("rag.a2a.error").count()).isEqualTo(1.0);
        // 对话链异常已进审计（record 在 chatRag 前），duration 不计时
        assertThat(meterRegistry.timer("rag.a2a.chat.duration").count()).isZero();
    }
}
