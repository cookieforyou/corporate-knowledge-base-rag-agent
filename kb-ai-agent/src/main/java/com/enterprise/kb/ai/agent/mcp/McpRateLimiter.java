package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.api.ratelimiter.RateLimiterArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 只读工具租户级限流（安全簇② B3，2026-08-17）
 *
 * <p><b>动因</b>：search/get_document 直调检索器/PG，不经 advisor 链——
 * 对话链的 RateLimitAdvisor(100) 覆盖不到，形成配额水位不对称（ask 经全链
 * 自动受限）。本组件为两只读工具补独立配额桶。
 *
 * <p><b>桶形态</b>：{@code rag:ratelimit:mcp:{tenantId}}——与对话链桶
 * （rag:ratelimit:tenant:*）**分账**：MCP 只读调用不挤占对话配额，
 * 对话洪峰也不吞噬 MCP 通道（专项方案 §4.2 B3 定案）。
 * RateType.OVERALL 全局口径 + 首触达 setRate 覆盖写，同 RateLimitAdvisor 形态。
 *
 * <p><b>容错策略（fail-open 定案）</b>：Redis 故障 → 放行 + 告警日志——
 * 只读路径的限流是可用性管控不是安全边界，Redis 抖动不设防
 * （与对话链配额护栏 fail-open 同语义；审批账本 fail-closed 是写路径另一族）。
 * 超限抛 {@code BusinessException("RATE_LIMITED")}：错误码复用对话链语义，
 * MCP 协议层经错误帧回传调用方。
 */
@Slf4j
@Component
public class McpRateLimiter {

    static final String KEY_PREFIX = "rag:ratelimit:mcp:";

    private final RedissonClient redissonClient;
    private final AiBusinessMetrics metrics;
    private final boolean enabled;
    private final long rate;
    private final long intervalSeconds;

    /** 本进程已完成配置写入的租户——避免每请求重复 setRate */
    private final Set<String> configuredTenants = ConcurrentHashMap.newKeySet();

    public McpRateLimiter(RedissonClient redissonClient,
                          AiBusinessMetrics metrics,
                          @Value("${rag.mcp.ratelimit.enabled:true}") boolean enabled,
                          @Value("${rag.mcp.ratelimit.tenant.rate:120}") long rate,
                          @Value("${rag.mcp.ratelimit.tenant.interval-seconds:60}") long intervalSeconds) {
        this.redissonClient = redissonClient;
        this.metrics = metrics;
        this.enabled = enabled;
        this.rate = rate;
        this.intervalSeconds = intervalSeconds;
        log.info("MCP 只读工具限流装配: enabled={}, rate={} 次/{}s", enabled, rate, intervalSeconds);
    }

    /**
     * 获取配额；超限抛 RATE_LIMITED，Redis 故障降级放行。
     *
     * @param tenantId 租户 ID（McpIdentityGuard fail-closed 后必非空）
     */
    public void acquire(String tenantId) {
        if (!enabled || tenantId == null || tenantId.isBlank()) {
            return;
        }
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(KEY_PREFIX + tenantId);
            if (configuredTenants.add(tenantId)) {
                limiter.setRate(RateLimiterArgs.of(RateType.OVERALL, rate, Duration.ofSeconds(intervalSeconds)));
            }
            if (!limiter.tryAcquire(1)) {
                metrics.recordMcpRateLimited();
                log.warn("租户 [{}] MCP 只读工具触发限流（{} 次/{}s），调用拒绝", tenantId, rate, intervalSeconds);
                throw new BusinessException("RATE_LIMITED", "请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("MCP 限流组件 Redis 故障，降级放行: {}", e.getMessage());
        }
    }
}
