package com.enterprise.kb.ai.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多模型智能路由（设计文档 11.2.2，任务 3.2）—— 主模型 + 备用模型熔断切换
 *
 * <p><b>实用形态定稿（2026-08-05 用户拍板）</b>：设计稿的 ECONOMY/STANDARD/PREMIUM
 * 三级复杂度路由在「主 + 备」双模型现状下三档形同虚设，且复杂度分类器引入误路由
 * 风险——复杂度分级移交 Phase 5.4 查询意图识别统一做；本期落地验收相关形态：
 * 主模型故障自动切换备用（验收「Failover 切换时间 < 5s」）+ 熔断器保护。
 *
 * <p><b>熔断器三态（无锁原子实现）</b>：
 * <ul>
 *   <li><b>CLOSED</b>（连续失败数 &lt; 阈值）：请求走主模型；成功即清零失败计数</li>
 *   <li><b>OPEN</b>（连续失败 ≥ 阈值，熔断窗口内）：请求直发备用模型，主模型零触达</li>
 *   <li><b>HALF_OPEN</b>（熔断窗口结束后的首个请求）：试探主模型——成功则闭合
 *       （计数清零），失败立即重开熔断（窗口续期）</li>
 * </ul>
 *
 * <p><b>失败即切（不丢请求）</b>：CLOSED/HALF_OPEN 态主模型调用抛错时，当次请求
 * 立即转发备用模型作答——故障切换对用户无感（切换耗时 = 主模型失败暴露耗时 +
 * 备用调用，快速失败类故障远低于 5s 验收线）。备用模型自身失败如实上抛
 * （双模型俱损已无路由可做，交由全局异常处理）。
 *
 * <p><b>流式语义</b>：主模型流错误经 onErrorResume 切备用流整段重发——错误发生在
 * 首 token 前（连接/鉴权/配额类故障的常态形态）用户无感；极少数已流出部分 token
 * 后中断的场景会出现内容重复，优于流中断报错，已知取舍。
 *
 * <p>模型层路由对上层零感知：chatClient（评估）与 agentChatClient（生产）注入
 * 本 Bean 即同时获得容灾；kb-eval 度量链路在模型故障时同样受保护。
 */
@Slf4j
public class SmartRoutingChatModel implements ChatModel {

    private final ChatModel primary;
    private final ChatModel fallback;
    private final int failureThreshold;
    private final long openSeconds;
    private final Clock clock;

    /** 主模型连续失败计数（成功清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断截止时间戳（毫秒）；当前时刻早于该值即 OPEN 态 */
    private final AtomicLong openUntilEpochMs = new AtomicLong(0);

    public SmartRoutingChatModel(ChatModel primary, ChatModel fallback,
                                 int failureThreshold, long openSeconds) {
        this(primary, fallback, failureThreshold, openSeconds, Clock.systemUTC());
    }

    /** Clock 注入供测试推进时间 */
    SmartRoutingChatModel(ChatModel primary, ChatModel fallback,
                          int failureThreshold, long openSeconds, Clock clock) {
        this.primary = primary;
        this.fallback = fallback;
        this.failureThreshold = failureThreshold;
        this.openSeconds = openSeconds;
        this.clock = clock;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (primaryBypassed()) {
            return fallback.call(prompt);
        }
        try {
            ChatResponse response = primary.call(prompt);
            recordSuccess();
            return response;
        } catch (RuntimeException e) {
            recordFailure(e);
            return fallback.call(prompt);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (primaryBypassed()) {
            return fallback.stream(prompt);
        }
        return primary.stream(prompt)
            .doOnNext(ignored -> recordSuccess())
            .onErrorResume(e -> {
                recordFailure(e);
                return fallback.stream(prompt);
            });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }

    @Override
    public ChatOptions getOptions() {
        return primary.getOptions();
    }

    // ── 熔断器 ──

    /** OPEN 态判定：失败数已达阈值且仍在熔断窗口内——直发备用，主模型零触达 */
    private boolean primaryBypassed() {
        if (consecutiveFailures.get() < failureThreshold) {
            return false;
        }
        return clock.millis() < openUntilEpochMs.get();
    }

    private void recordFailure(Throwable cause) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntilEpochMs.set(clock.millis() + openSeconds * 1000);
            log.warn("主模型连续失败 {} 次（阈值 {}），熔断 OPEN {}s，期间请求直发备用模型。最近原因: {}",
                failures, failureThreshold, openSeconds, cause.getMessage());
        } else {
            log.warn("主模型调用失败（连续 {}/{}），本次切换备用模型: {}",
                failures, failureThreshold, cause.getMessage());
        }
    }

    private void recordSuccess() {
        if (consecutiveFailures.get() > 0) {
            log.info("主模型恢复（HALF_OPEN 试探成功），熔断闭合");
        }
        consecutiveFailures.set(0);
    }
}
