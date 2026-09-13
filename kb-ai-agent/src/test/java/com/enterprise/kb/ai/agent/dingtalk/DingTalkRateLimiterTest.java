package com.enterprise.kb.ai.agent.dingtalk;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DingTalkRateLimiter 测试（Phase5簇⑥ 5.12，McpRateLimiterTest 同构）：
 * 独立桶形态、超限拒绝、fail-open 降级。
 */
class DingTalkRateLimiterTest {

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private SimpleMeterRegistry meterRegistry;
    private DingTalkRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        DingTalkProperties properties = new DingTalkProperties();
        rateLimiter = new DingTalkRateLimiter(redisson, new AiBusinessMetrics(meterRegistry), properties);
    }

    @Test
    void bucketKeyAndRatePinned() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        rateLimiter.acquire("tenant-a");

        // 独立桶命名与对话链/MCP 桶三分账
        verify(redisson).getRateLimiter("rag:ratelimit:dingtalk:tenant-a");
        // 首触达 setRate 覆盖写：配置缺省 20 次/60s（群聊低频保守起步）——
        // RateLimiterArgs.of 即 Params 便捷工厂，captor 断言字段（McpRateLimiterTest 同款）
        ArgumentCaptor<RateLimiterArgs> captor = ArgumentCaptor.forClass(RateLimiterArgs.class);
        verify(limiter).setRate(captor.capture());
        org.redisson.api.ratelimiter.RateLimiterParams params =
            (org.redisson.api.ratelimiter.RateLimiterParams) captor.getValue();
        assertThat(params.getMode()).isEqualTo(RateType.OVERALL);
        assertThat(params.getRate()).isEqualTo(20L);
        // Redisson 新版 Params 的 interval 为 Duration 形态（非毫秒 long）
        assertThat(params.getRateInterval()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @Test
    void overLimitRejectedWithCounter() {
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertThatThrownBy(() -> rateLimiter.acquire("tenant-a"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(Constants.ErrorCodes.RATE_LIMITED);
        assertThat(meterRegistry.counter("rag.dingtalk.ratelimited").count()).isEqualTo(1.0);
    }

    @Test
    void redisFailureFailsOpen() {
        Mockito.doThrow(new RuntimeException("redis down")).when(limiter).setRate(Mockito.any(RateLimiterArgs.class));

        // fail-open：可用性管控非安全边界，Redis 抖动不设防
        assertThatCode(() -> rateLimiter.acquire("tenant-a")).doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterPassThrough() {
        DingTalkProperties properties = new DingTalkProperties();
        properties.getRatelimit().setEnabled(false);
        DingTalkRateLimiter disabled = new DingTalkRateLimiter(
            redisson, new AiBusinessMetrics(new SimpleMeterRegistry()), properties);

        disabled.acquire("tenant-a");

        verify(redisson, Mockito.never()).getRateLimiter(anyString());
    }
}
