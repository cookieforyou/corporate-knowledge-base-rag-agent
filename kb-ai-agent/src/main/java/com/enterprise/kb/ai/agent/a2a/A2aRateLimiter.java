package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A 端点租户级限流（Phase5簇⑥ 批2，McpRateLimiter/DingTalkRateLimiter 同构）
 *
 * <p><b>动因</b>：机对机通道无人类节奏约束，对话链 RateLimitAdvisor(100) 经
 * advisor 参数链虽对 chatRag 生效，但入口域独立计数口径更清晰——本桶是入口域
 * 第一道闸。
 *
 * <p><b>桶形态</b>：{@code rag:ratelimit:a2a:{tenantId}}——与对话链桶
 * （rag:ratelimit:tenant:*）、MCP/钉钉桶四分账。RateType.OVERALL 全局口径 +
 * 首触达 setRate 覆盖写，同 RateLimitAdvisor 形态。
 *
 * <p><b>容错（fail-open）</b>：Redis 故障放行 + 告警日志——限流是可用性管控
 * 非安全边界（租户隔离由身份守卫与检索过滤承载）。超限抛 RATE_LIMITED
 * （协议层转 JSON-RPC error），错误码复用对话链语义。
 */
@Slf4j
@Component
public class A2aRateLimiter {

    static final String KEY_PREFIX = "rag:ratelimit:a2a:";

    private final RedissonClient redissonClient;
    private final AiBusinessMetrics metrics;
    private final A2aProperties properties;

    /** 本进程已完成配置写入的租户——避免每请求重复 setRate */
    private final Set<String> configuredTenants = ConcurrentHashMap.newKeySet();

    public A2aRateLimiter(RedissonClient redissonClient,
                          AiBusinessMetrics metrics,
                          A2aProperties properties) {
        this.redissonClient = redissonClient;
        this.metrics = metrics;
        this.properties = properties;
        log.info("A2A 端点限流装配: enabled={}, rate={} 次/{}s",
            properties.getRatelimit().isEnabled(),
            properties.getRatelimit().getRate(),
            properties.getRatelimit().getIntervalSeconds());
    }

    /**
     * 获取配额；超限抛 RATE_LIMITED（协议层转 JSON-RPC error），Redis 故障降级放行。
     *
     * @param tenantId 租户 ID（身份守卫 fail-closed 后必非空）
     */
    public void acquire(String tenantId) {
        A2aProperties.RateLimit config = properties.getRatelimit();
        if (!config.isEnabled() || tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(KEY_PREFIX + tenantId);
            if (configuredTenants.add(tenantId)) {
                limiter.setRate(RateLimiterArgs.of(RateType.OVERALL,
                    config.getRate(), Duration.ofSeconds(config.getIntervalSeconds())));
            }
            if (!limiter.tryAcquire(1)) {
                metrics.recordA2aRateLimited();
                log.warn("租户 [{}] A2A 请求触发限流（{} 次/{}s），请求拒绝",
                    tenantId, config.getRate(), config.getIntervalSeconds());
                throw new BusinessException(Constants.ErrorCodes.RATE_LIMITED, "请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("A2A 限流组件 Redis 故障，降级放行: {}", e.getMessage());
        }
    }
}
