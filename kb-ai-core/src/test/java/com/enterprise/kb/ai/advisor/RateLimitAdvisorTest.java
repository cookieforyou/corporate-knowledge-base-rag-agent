package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 租户级限流护栏测试（3.7）—— 令牌桶放行/拒绝、无租户跳过、Redis 故障降级
 */
class RateLimitAdvisorTest {

    private RedissonClient redisson;
    private RRateLimiter limiter;
    private AdvisorChain chain;
    private RateLimitAdvisor advisor;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        chain = mock(AdvisorChain.class);
        when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
        advisor = new RateLimitAdvisor(redisson, true, 60, 60);
    }

    private static ChatClientRequest requestWithTenant(String tenantId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage("什么是增值税发票？"))),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));
    }

    @Test
    void withinLimitPassesThroughUnchanged() {
        when(limiter.tryAcquire(1)).thenReturn(true);
        ChatClientRequest request = requestWithTenant("tenant-a");

        assertThat(advisor.before(request, chain)).isSameAs(request);
        verify(limiter).setRate(eq(RateType.OVERALL), eq(60L), eq(Duration.ofSeconds(60)));
    }

    @Test
    void exceedingLimitThrowsRateLimited() {
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertThatThrownBy(() -> advisor.before(requestWithTenant("tenant-a"), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("RATE_LIMITED");
    }

    @Test
    void rateConfigWrittenOnlyOncePerTenant() {
        when(limiter.tryAcquire(1)).thenReturn(true);
        ChatClientRequest request = requestWithTenant("tenant-a");

        advisor.before(request, chain);
        advisor.before(request, chain);

        // 进程内每租户仅首次触达写配置；每请求都要消耗令牌
        verify(limiter, times(1)).setRate(any(RateType.class), anyLong(), any(Duration.class));
        verify(limiter, times(2)).tryAcquire(1);
    }

    @Test
    void distinctTenantsUseDistinctBuckets() {
        when(limiter.tryAcquire(1)).thenReturn(true);

        advisor.before(requestWithTenant("tenant-a"), chain);
        advisor.before(requestWithTenant("tenant-b"), chain);

        verify(redisson).getRateLimiter(RateLimitAdvisor.KEY_PREFIX + "tenant-a");
        verify(redisson).getRateLimiter(RateLimitAdvisor.KEY_PREFIX + "tenant-b");
    }

    @Test
    void missingRetrievalContextSkipsRateLimit() {
        ChatClientRequest bare = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of());

        assertThat(advisor.before(bare, chain)).isSameAs(bare);
        verifyNoInteractions(redisson);
    }

    @Test
    void blankTenantSkipsRateLimit() {
        assertThat(advisor.before(requestWithTenant("  "), chain)).isNotNull();
        verifyNoInteractions(redisson);
    }

    @Test
    void redisFailureDegradesToPassThrough() {
        when(redisson.getRateLimiter(anyString()))
            .thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(advisor.before(requestWithTenant("tenant-a"), chain)).isNotNull();
    }

    @Test
    void disabledSkipsRateLimit() {
        RateLimitAdvisor disabled = new RateLimitAdvisor(redisson, false, 60, 60);

        assertThat(disabled.before(requestWithTenant("tenant-a"), chain)).isNotNull();
        verifyNoInteractions(redisson);
    }

    @Test
    void rateLimitRejectionDoesNotSwallowIntoDegrade() {
        // 拒绝异常必须原样上抛，不能被 Redis 故障降级分支吞掉
        when(limiter.tryAcquire(1)).thenReturn(false);

        assertThatThrownBy(() -> advisor.before(requestWithTenant("tenant-a"), chain))
            .isInstanceOf(BusinessException.class);
        verify(limiter, never()).tryAcquire(2);
    }
}
