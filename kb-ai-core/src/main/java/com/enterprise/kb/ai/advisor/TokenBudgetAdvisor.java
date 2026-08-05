package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.TokenBudgetExceededException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 租户级 Token 预算护栏（设计文档 12.3，任务 3.8）—— Redis 日预算 + Micrometer 成本追踪
 *
 * <p>Order 30：链序「先审计 → 再鉴权 → 后限流/预算」中的预算位，先于
 * RateLimit(100)——预算耗尽的租户无需再占令牌桶配额。
 *
 * <p><b>预算账本</b>：每租户每日一键 {@code rag:token-budget:{tenant}:{日期}}
 * （{@link RAtomicLong}，首次写入挂 2 日 TTL 自动清理）。before() 读账本拒超额，
 * after() 以响应 usage 回写消耗。草图的 SINGLE_REQUEST_BUDGET 不实现：单次请求
 * token 上限已由模型侧 max-tokens（application-ai.yml）硬约束，重复设限无增益。
 *
 * <p><b>租户身份来源</b>：同 RateLimitAdvisor，经 {@link RetrievalContext} 参数链
 * （草图的 AuthAdvisor 上下文写入为虚构前提，3.9 实际落地为 Controller 身份守卫）。
 *
 * <p><b>容错策略</b>：Redis 故障 before 降级放行 / after 降级丢弃计数——成本追踪
 * 短时失准可接受，击穿问答不可接受（与 FaultTolerantChatMemory / rerank 降级同策）。
 *
 * <p><b>流式已知限制（v2.6 实证记录）</b>：BaseAdvisor 默认 adviseStream 仅对末块
 * 执行 after()；OpenAI 兼容流式响应的 usage 需 {@code stream_options.include_usage}
 * 开启才随末块下发，当前自动装配的 deepSeekChatModel 未开启——流式消耗暂不计账
 * （同步路径计量完整）。开启 streamUsage 涉及模型装配变更，列为后续增强项。
 */
@Slf4j
@Component
public class TokenBudgetAdvisor implements BaseAdvisor {

    static final String KEY_PREFIX = "rag:token-budget:";

    private final RedissonClient redissonClient;
    private final boolean enabled;
    private final long dailyLimit;

    /** AI Token 总消耗（12.3：rag.token.total）——不带租户标签，避免指标基数膨胀 */
    private final Counter tokenCounter;
    /** 预算拒绝次数——攻击/异常用量观测锚点（3.13 指标体系落地后可迁 AiBusinessMetrics） */
    private final Counter rejectedCounter;

    public TokenBudgetAdvisor(RedissonClient redissonClient,
                              MeterRegistry meterRegistry,
                              @Value("${rag.token-budget.enabled:true}") boolean enabled,
                              @Value("${rag.token-budget.daily-limit:1000000}") long dailyLimit) {
        this.redissonClient = redissonClient;
        this.enabled = enabled;
        this.dailyLimit = dailyLimit;
        this.tokenCounter = Counter.builder("rag.token.total")
            .description("AI Token 总消耗")
            .register(meterRegistry);
        this.rejectedCounter = Counter.builder("rag.token.budget.rejected")
            .description("Token 预算拒绝次数")
            .register(meterRegistry);
        log.info("Token 预算护栏装配: enabled={}, dailyLimit={}", enabled, dailyLimit);
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
            RAtomicLong ledger = redissonClient.getAtomicLong(keyOf(tenantId, LocalDate.now()));
            ledger.expireIfNotSet(Duration.ofDays(2));
            long used = ledger.get();
            if (used >= dailyLimit) {
                rejectedCounter.increment();
                log.warn("租户 [{}] 日 Token 预算耗尽（已用 {} / 上限 {}），请求拒绝",
                    tenantId, used, dailyLimit);
                throw new TokenBudgetExceededException(
                    "日 Token 预算已耗尽（已用 " + used + " / 上限 " + dailyLimit + "）");
            }
        } catch (TokenBudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            // Redis 故障降级放行：宁可短时成本失察，不可击穿问答
            log.warn("Token 预算组件 Redis 故障，降级放行: {}", e.getMessage());
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long tokens = totalTokensOf(response);
        if (tokens > 0) {
            tokenCounter.increment(tokens);
            String tenantId = tenantOf(response);
            if (tenantId != null) {
                try {
                    RAtomicLong ledger = redissonClient.getAtomicLong(keyOf(tenantId, LocalDate.now()));
                    ledger.addAndGet(tokens);
                    ledger.expireIfNotSet(Duration.ofDays(2));
                } catch (Exception e) {
                    log.warn("Token 消耗回写失败（租户 {}，{} tokens），计数丢弃: {}",
                        tenantId, tokens, e.getMessage());
                }
            }
        }
        return response;
    }

    @Override
    public int getOrder() {
        return 30;
    }

    static String keyOf(String tenantId, LocalDate date) {
        return KEY_PREFIX + tenantId + ":" + date;
    }

    /** 响应 Token 用量（null 安全）；流式末块无 usage 时返回 0 */
    private static long totalTokensOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return 0L;
        }
        Usage usage = response.chatResponse().getMetadata().getUsage();
        // 实证：2.0 GA Usage.getTotalTokens() 返回 Integer（可空），非原始 long
        Integer total = usage == null ? null : usage.getTotalTokens();
        return total == null ? 0L : total.longValue();
    }

    /** 经 RetrievalContext 参数链提取租户；缺失返回 null（请求/响应两侧上下文同源透传） */
    private static String tenantOf(ChatClientRequest request) {
        Object value = request.context().get(RetrievalContext.CONTEXT_KEY);
        return value instanceof RetrievalContext ctx ? validTenant(ctx) : null;
    }

    private static String tenantOf(ChatClientResponse response) {
        Object value = response.context().get(RetrievalContext.CONTEXT_KEY);
        return value instanceof RetrievalContext ctx ? validTenant(ctx) : null;
    }

    private static String validTenant(RetrievalContext ctx) {
        String tenantId = ctx.getTenantId();
        return tenantId != null && !tenantId.isBlank() ? tenantId : null;
    }
}
