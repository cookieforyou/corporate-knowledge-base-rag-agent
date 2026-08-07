package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.TokenBudgetExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Token 预算护栏测试（3.8）—— 日预算拒绝、消耗回写、Micrometer 计数、
 * Redis 故障降级、空 usage 安全
 */
class TokenBudgetAdvisorTest {

    private static final long DAILY_LIMIT = 1_000_000L;

    private RedissonClient redisson;
    private RAtomicLong ledger;
    private SimpleMeterRegistry meterRegistry;
    private AdvisorChain chain;
    private TokenBudgetAdvisor advisor;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        ledger = mock(RAtomicLong.class);
        meterRegistry = new SimpleMeterRegistry();
        chain = mock(AdvisorChain.class);
        when(redisson.getAtomicLong(anyString())).thenReturn(ledger);
        advisor = new TokenBudgetAdvisor(redisson, new AiBusinessMetrics(meterRegistry), true, DAILY_LIMIT);
    }

    private static ChatClientRequest requestWithTenant(String tenantId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage("什么是增值税发票？"))),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));
    }

    private static ChatClientResponse responseWithUsage(String tenantId, int promptTokens, int completionTokens) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, completionTokens))
            .build();
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(
                List.of(new Generation(new AssistantMessage("回答"))), metadata))
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx))
            .build();
    }

    // ── before()：预算检查 ──

    @Test
    void underBudgetPassesThrough() {
        when(ledger.get()).thenReturn(100L);

        ChatClientRequest request = requestWithTenant("tenant-a");
        assertThat(advisor.before(request, chain)).isSameAs(request);
        verify(ledger).expireIfNotSet(Duration.ofDays(2));
    }

    @Test
    void exhaustedBudgetThrowsTokenBudgetExceeded() {
        when(ledger.get()).thenReturn(DAILY_LIMIT);

        assertThatThrownBy(() -> advisor.before(requestWithTenant("tenant-a"), chain))
            .isInstanceOf(TokenBudgetExceededException.class)
            .hasMessageContaining("1000000");

        assertThat(meterRegistry.counter("rag.token.budget.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void missingRetrievalContextSkipsBudgetCheck() {
        ChatClientRequest bare = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of());

        assertThat(advisor.before(bare, chain)).isSameAs(bare);
        verifyNoInteractions(redisson);
    }

    @Test
    void redisFailureDegradesToPassThrough() {
        when(redisson.getAtomicLong(anyString()))
            .thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(advisor.before(requestWithTenant("tenant-a"), chain)).isNotNull();
    }

    @Test
    void disabledSkipsBudgetCheck() {
        TokenBudgetAdvisor disabled = new TokenBudgetAdvisor(redisson, new AiBusinessMetrics(meterRegistry), false, DAILY_LIMIT);

        assertThat(disabled.before(requestWithTenant("tenant-a"), chain)).isNotNull();
        verifyNoInteractions(redisson);
    }

    // ── after()：消耗回写 + Micrometer ──

    @Test
    void usageRecordedToLedgerAndCounter() {
        ChatClientResponse response = responseWithUsage("tenant-a", 100, 50);

        assertThat(advisor.after(response, chain)).isSameAs(response);
        verify(ledger).addAndGet(150L);
        assertThat(meterRegistry.counter("rag.token.total").count()).isEqualTo(150.0);
    }

    @Test
    void nullUsageIsNoOp() {
        // 无 usage 元数据（流式末块未开 include_usage 的常态形态）
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ChatClientResponse noUsage = ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("x")))))
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx))
            .build();

        assertThat(advisor.after(noUsage, chain)).isSameAs(noUsage);
        verifyNoInteractions(redisson);
        assertThat(meterRegistry.counter("rag.token.total").count()).isZero();
    }

    @Test
    void redisFailureInAfterDropsCountSilently() {
        when(ledger.addAndGet(150L)).thenThrow(new RuntimeException("Redis connection refused"));

        ChatClientResponse response = responseWithUsage("tenant-a", 100, 50);
        assertThat(advisor.after(response, chain)).isSameAs(response);
    }

    @Test
    void emptyResponseIsSafe() {
        ChatClientResponse empty = ChatClientResponse.builder().context(Map.of()).build();

        assertThat(advisor.after(empty, chain)).isSameAs(empty);
        verifyNoInteractions(redisson);
    }

    // ── 键形态 ──

    @Test
    void dailyKeyCombinesTenantAndDate() {
        assertThat(TokenBudgetAdvisor.keyOf("tenant-a", LocalDate.of(2026, 8, 5)))
            .isEqualTo("rag:token-budget:tenant-a:2026-08-05");
    }
}
