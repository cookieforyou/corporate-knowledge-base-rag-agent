package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;
import org.redisson.api.ratelimiter.RateLimiterParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpRateLimiter 测试（安全簇② B3）：独立桶形态、超限拒绝、fail-open 降级
 */
class McpRateLimiterTest {

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private SimpleMeterRegistry meterRegistry;
    private McpRateLimiter mcpRateLimiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        mcpRateLimiter = new McpRateLimiter(redisson, new AiBusinessMetrics(meterRegistry), true, 120, 60);
    }

    @Test
    void bucketKeyAndRatePinned() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        mcpRateLimiter.acquire("tenant-a");

        // 独立桶命名与对话链分账（rag:ratelimit:mcp:* vs rag:ratelimit:tenant:*）
        verify(redisson).getRateLimiter("rag:ratelimit:mcp:tenant-a");
        ArgumentCaptor<RateLimiterArgs> captor = ArgumentCaptor.forClass(RateLimiterArgs.class);
        verify(limiter).setRate(captor.capture());
        RateLimiterParams params = (RateLimiterParams) captor.getValue();
        assertThat(params.getMode()).isEqualTo(RateType.OVERALL);
        assertThat(params.getRate()).isEqualTo(120L);
    }

    @Test
    void withinLimitPasses() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        assertThatCode(() -> mcpRateLimiter.acquire("tenant-a")).doesNotThrowAnyException();
        assertThat(meterRegistry.counter("rag.guardrail.mcp.ratelimited").count()).isZero();
    }

    @Test
    void exceededRejectsWithRateLimitedAndCounts() {
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertThatThrownBy(() -> mcpRateLimiter.acquire("tenant-a"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("RATE_LIMITED");
        assertThat(meterRegistry.counter("rag.guardrail.mcp.ratelimited").count()).isEqualTo(1.0);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisson.getRateLimiter(anyString())).thenThrow(new RuntimeException("Redis 不可达"));

        // fail-open 定案：只读路径限流是可用性管控非安全边界，Redis 抖动不设防
        assertThatCode(() -> mcpRateLimiter.acquire("tenant-a")).doesNotThrowAnyException();
    }

    @Test
    void disabledSkipsEntirely() {
        McpRateLimiter disabled = new McpRateLimiter(redisson, new AiBusinessMetrics(meterRegistry), false, 120, 60);

        disabled.acquire("tenant-a");

        org.mockito.Mockito.verifyNoInteractions(redisson);
    }

    @Test
    void blankTenantSkipped() {
        mcpRateLimiter.acquire("  ");
        mcpRateLimiter.acquire(null);

        org.mockito.Mockito.verifyNoInteractions(redisson);
    }
}
