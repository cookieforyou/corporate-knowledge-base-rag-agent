package com.enterprise.kb.ai.agent.a2a;

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
import org.redisson.api.ratelimiter.RateLimiterParams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2aRateLimiter 测试（Phase5簇⑥ 批2，DingTalkRateLimiterTest 同构）：
 * 独立桶形态、超限拒绝、fail-open 降级。
 */
class A2aRateLimiterTest {

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private SimpleMeterRegistry meterRegistry;
    private A2aRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        rateLimiter = new A2aRateLimiter(redisson, new AiBusinessMetrics(meterRegistry), new A2aProperties());
    }

    @Test
    void bucketKeyAndRatePinned() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        rateLimiter.acquire("tenant-a");

        // 独立桶命名与对话链/MCP/钉钉桶四分账
        verify(redisson).getRateLimiter("rag:ratelimit:a2a:tenant-a");
        // 首触达 setRate 覆盖写：配置缺省 20 次/60s（机对机通道 MCP 同档起步）——
        // RateLimiterArgs.of 即 Params 便捷工厂，captor 断言字段
        ArgumentCaptor<RateLimiterArgs> captor = ArgumentCaptor.forClass(RateLimiterArgs.class);
        verify(limiter).setRate(captor.capture());
        RateLimiterParams params = (RateLimiterParams) captor.getValue();
        assertThat(params.getMode()).isEqualTo(RateType.OVERALL);
        assertThat(params.getRate()).isEqualTo(20L);
        assertThat(params.getRateInterval()).isEqualTo(java.time.Duration.ofSeconds(60));
    }

    @Test
    void overLimitRejectedWithCounter() {
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertThatThrownBy(() -> rateLimiter.acquire("tenant-a"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(Constants.ErrorCodes.RATE_LIMITED);
        assertThat(meterRegistry.counter("rag.a2a.ratelimited").count()).isEqualTo(1.0);
    }

    @Test
    void redisFailureFailsOpen() {
        Mockito.doThrow(new RuntimeException("redis down")).when(limiter).setRate(Mockito.any(RateLimiterArgs.class));

        // fail-open：可用性管控非安全边界，Redis 抖动不设防
        assertThatCode(() -> rateLimiter.acquire("tenant-a")).doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterPassThrough() {
        A2aProperties properties = new A2aProperties();
        properties.getRatelimit().setEnabled(false);
        A2aRateLimiter disabled = new A2aRateLimiter(
            redisson, new AiBusinessMetrics(new SimpleMeterRegistry()), properties);

        disabled.acquire("tenant-a");

        verify(redisson, Mockito.never()).getRateLimiter(anyString());
    }
}
