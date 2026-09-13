package com.enterprise.kb.ai.agent.dingtalk;

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
 * 钉钉群机器人租户级限流（Phase5簇⑥ 5.12，McpRateLimiter 同构）
 *
 * <p><b>动因</b>：群机器人 @ 消息不经 HTTP 入口，对话链 RateLimitAdvisor(100)
 * 经 advisor 参数链虽对 chatRag 生效，但入口域独立计数口径更清晰、群聊洪峰
 * （@ 刷屏）先于对话链配额拦截——本桶是入口域第一道闸。
 *
 * <p><b>桶形态</b>：{@code rag:ratelimit:dingtalk:{tenantId}}——与对话链桶
 * （rag:ratelimit:tenant:*）、MCP 只读桶三分账。RateType.OVERALL 全局口径 +
 * 首触达 setRate 覆盖写，同 RateLimitAdvisor 形态。
 *
 * <p><b>容错（fail-open）</b>：Redis 故障放行 + 告警日志——群聊限流是可用性
 * 管控非安全边界（租户隔离由身份绑定与检索过滤承载）。超限由调用方回复
 * 限流话术（BusinessException 上抛，错误码复用对话链 RATE_LIMITED 语义）。
 */
@Slf4j
@Component
public class DingTalkRateLimiter {

    static final String KEY_PREFIX = "rag:ratelimit:dingtalk:";

    private final RedissonClient redissonClient;
    private final AiBusinessMetrics metrics;
    private final DingTalkProperties properties;

    /** 本进程已完成配置写入的租户——避免每消息重复 setRate */
    private final Set<String> configuredTenants = ConcurrentHashMap.newKeySet();

    public DingTalkRateLimiter(RedissonClient redissonClient,
                               AiBusinessMetrics metrics,
                               DingTalkProperties properties) {
        this.redissonClient = redissonClient;
        this.metrics = metrics;
        this.properties = properties;
        log.info("钉钉群机器人限流装配: enabled={}, rate={} 次/{}s",
            properties.getRatelimit().isEnabled(),
            properties.getRatelimit().getRate(),
            properties.getRatelimit().getIntervalSeconds());
    }

    /**
     * 获取配额；超限抛 RATE_LIMITED（调用方回复限流话术），Redis 故障降级放行。
     *
     * @param tenantId 租户 ID（D2-A 启动期绑定后必非空）
     */
    public void acquire(String tenantId) {
        DingTalkProperties.RateLimit config = properties.getRatelimit();
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
                metrics.recordDingTalkRateLimited();
                log.warn("租户 [{}] 钉钉机器人触发限流（{} 次/{}s），消息拒绝",
                    tenantId, config.getRate(), config.getIntervalSeconds());
                throw new BusinessException(Constants.ErrorCodes.RATE_LIMITED, "请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("钉钉限流组件 Redis 故障，降级放行: {}", e.getMessage());
        }
    }
}
