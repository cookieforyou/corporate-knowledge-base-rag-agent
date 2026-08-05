package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户级限流护栏（设计文档 11.2 链序表，任务 3.7）—— Redisson 令牌桶
 *
 * <p>Order 100：位于 TokenBudget(30) 之后、OutputGuardrail(110) 之前。
 * v2 链序重排原则「先审计 → 再鉴权 → 后限流/预算」：限流依赖租户身份，
 * 且被限流请求不应再消耗下游护栏/记忆/检索资源。
 *
 * <p><b>租户身份来源（v2.6 实现期定稿）</b>：草图假设 AuthAdvisor(20) 向
 * advisor 上下文写入 {@code tenant_id}——实际 3.9 落地形态为 Controller 入口
 * 身份守卫，租户身份经 {@link RetrievalContext} 参数链传递（与检索/记忆同款
 * 机制，不用 @RequestScope/ThreadLocal）。本 Advisor 从请求上下文读取
 * {@code RetrievalContext.CONTEXT_KEY} 取 tenantId。
 *
 * <p><b>令牌桶形态</b>：每租户独立 {@link RRateLimiter}
 * （key {@code rag:ratelimit:tenant:{tenantId}}，RateType.OVERALL 全局口径——
 * 同租户多实例共享配额）。进程对每租户首次触达以 {@code setRate} 覆盖式写入
 * 当前配置（配置为单一事实源，Redis 残留旧配置自动刷新）。
 *
 * <p><b>容错策略（与项目降级体系同策）</b>：① Redis 故障 → 放行 + 告警日志，
 * 限流是可用性管控不是安全边界，Redis 抖动不击穿问答（FaultTolerantChatMemory /
 * rerank 降级同款）；② 请求上下文缺租户 → 跳过限流——生产链路由 Controller
 * fail-closed 身份守卫保证此处必有租户（缺失早已 400 IDENTITY_INCOMPLETE），
 * 本分支仅为防御纵深，绝不因限流组件自身问题拒绝请求。
 *
 * <p>超限抛 {@code BusinessException("RATE_LIMITED")}：同步路径经
 * GlobalExceptionHandler 映射 HTTP 429；流式路径由 AgentController
 * onErrorResume 承接为 SSE ERROR 事件（与 PROMPT_INJECTION 同形态）。
 */
@Slf4j
@Component
public class RateLimitAdvisor implements BaseAdvisor {

    static final String KEY_PREFIX = "rag:ratelimit:tenant:";

    private final RedissonClient redissonClient;
    private final boolean enabled;
    private final long rate;
    private final long intervalSeconds;

    /** 本进程已完成配置写入的租户——避免每请求重复 setRate */
    private final Set<String> configuredTenants = ConcurrentHashMap.newKeySet();

    public RateLimitAdvisor(RedissonClient redissonClient,
                            @Value("${rag.ratelimit.enabled:true}") boolean enabled,
                            @Value("${rag.ratelimit.tenant.rate:60}") long rate,
                            @Value("${rag.ratelimit.tenant.interval-seconds:60}") long intervalSeconds) {
        this.redissonClient = redissonClient;
        this.enabled = enabled;
        this.rate = rate;
        this.intervalSeconds = intervalSeconds;
        log.info("租户限流护栏装配: enabled={}, rate={} 次/{}s", enabled, rate, intervalSeconds);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) {
            return request;
        }
        String tenantId = tenantOf(request);
        if (tenantId == null) {
            // 防御纵深分支：生产链路 Controller 身份守卫已保证租户非空（fail-closed）
            return request;
        }
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(KEY_PREFIX + tenantId);
            if (configuredTenants.add(tenantId)) {
                // 覆盖式写入：配置（yml/环境变量）为单一事实源，刷新 Redis 残留旧配置
                limiter.setRate(RateType.OVERALL, rate, Duration.ofSeconds(intervalSeconds));
            }
            if (!limiter.tryAcquire(1)) {
                log.warn("租户 [{}] 触发限流（{} 次/{}s），请求拒绝", tenantId, rate, intervalSeconds);
                throw new BusinessException("RATE_LIMITED", "请求过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 故障降级放行：限流失效只损失可用性管控，不击穿问答
            log.warn("限流组件 Redis 故障，降级放行: {}", e.getMessage());
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    /** 经 RetrievalContext 参数链提取租户；缺失返回 null */
    private static String tenantOf(ChatClientRequest request) {
        Object value = request.context().get(RetrievalContext.CONTEXT_KEY);
        if (value instanceof RetrievalContext ctx) {
            String tenantId = ctx.getTenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                return tenantId;
            }
        }
        return null;
    }
}
