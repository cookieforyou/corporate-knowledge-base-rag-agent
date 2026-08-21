package com.enterprise.kb.ai.routing;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.CoreSubscriber;
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
 * <p><b>流式 trace 传播（簇⑥ 批4 残余修复，13 章 v2.58）</b>：Spring AI 2.0 GA 流式
 * 链的 chat_model 观测只经 Reactor Context 传播（internalStream contextWrite），不在
 * 订阅线程开 ThreadLocal 作用域；而模型 HTTP 层（SpringAiOpenAiHttpClient 的 OkHttp
 * Observation 拦截器）按**线程当前观测**寻父，且其 dispatcher 执行器经
 * ContextExecutorService 捕获的是 enqueue 时刻（Flux.create 消费者内）的线程上下文——
 * 该处 ThreadLocal 为空，POST span 遂成独立 trace root（v2.32 登记残余）。修复：
 * stream() 在订阅期经 {@link #openParentScopeOnSubscribe} 将 Context 中的父观测开为
 * 作用域——同步订阅传播直达 enqueue，OkHttp dispatcher 捕获到父观测，POST span
 * 挂回父链（chat_client 观测之下，与 gen_ai 生成 span 同层合树）。
 *
 * <p><b>双供应商 SLA 计数（簇⑥ 批4）</b>：熔断 OPEN 转入（含试探失败重开）/
 * HALF_OPEN 试探 / 备用接管三事件经 {@link AiBusinessMetrics} 落 Prometheus
 * （rag.routing.circuit.* / rag.routing.fallback.invoked），供供应商 SLA 面板与
 * KbPrimaryModelDegraded 告警消费。
 *
 * <p>模型层路由对上层零感知：chatClient（评估）与 ragAgentChatClient/toolAgentChatClient（生产双链）注入
 * 本 Bean 即同时获得容灾；kb-eval 度量链路在模型故障时同样受保护。
 */
@Slf4j
public class SmartRoutingChatModel implements ChatModel {

    private final ChatModel primary;
    private final ChatModel fallback;
    private final int failureThreshold;
    private final long openSeconds;
    private final Clock clock;
    private final AiBusinessMetrics metrics;

    /** 主模型连续失败计数（成功清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    /** 熔断截止时间戳（毫秒）；当前时刻早于该值即 OPEN 态 */
    private final AtomicLong openUntilEpochMs = new AtomicLong(0);

    public SmartRoutingChatModel(ChatModel primary, ChatModel fallback,
                                 int failureThreshold, long openSeconds, AiBusinessMetrics metrics) {
        this(primary, fallback, failureThreshold, openSeconds, Clock.systemUTC(), metrics);
    }

    /** Clock 注入供测试推进时间 */
    SmartRoutingChatModel(ChatModel primary, ChatModel fallback,
                          int failureThreshold, long openSeconds, Clock clock, AiBusinessMetrics metrics) {
        this.primary = primary;
        this.fallback = fallback;
        this.failureThreshold = failureThreshold;
        this.openSeconds = openSeconds;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (primaryBypassed()) {
            metrics.recordFallbackInvoked();
            return fallback.call(retargetToFallback(prompt));
        }
        recordHalfOpenProbeIfDue();
        try {
            ChatResponse response = primary.call(prompt);
            recordSuccess();
            return response;
        } catch (RuntimeException e) {
            recordFailure(e);
            metrics.recordFallbackInvoked();
            return fallback.call(retargetToFallback(prompt));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.deferContextual(contextView -> {
            // 订阅期父观测（簇⑥ 批4 trace 修复）：DefaultChatClient 已把 chat_client
            // 观测写入 Context（KEY=micrometer.observation），取出供订阅线程开作用域
            Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            if (primaryBypassed()) {
                metrics.recordFallbackInvoked();
                return openParentScopeOnSubscribe(parentObservation, fallback.stream(retargetToFallback(prompt)));
            }
            recordHalfOpenProbeIfDue();
            Flux<ChatResponse> routed = primary.stream(prompt)
                .doOnNext(ignored -> recordSuccess())
                .onErrorResume(e -> {
                    recordFailure(e);
                    metrics.recordFallbackInvoked();
                    // 备用重订阅发生在错误信号线程（非首次订阅线程），独立再包作用域
                    return openParentScopeOnSubscribe(parentObservation, fallback.stream(retargetToFallback(prompt)));
                });
            return openParentScopeOnSubscribe(parentObservation, routed);
        });
    }

    /**
     * 订阅线程开父观测作用域（簇⑥ 批4 trace 残余修复）：返回的 Flux 在 subscribe
     * 信号传播瞬间打开 parentObservation 作用域——同步订阅链直达模型 HTTP 层
     * enqueue（Flux.create 消费者），OkHttp dispatcher 的 ContextExecutorService
     * 捕获该 ThreadLocal 并在回调线程恢复，POST span 遂寻得父观测不再成独立 trace。
     * 父观测缺省（评估宿主等无观测环境）原样透传。
     */
    private static <T> Flux<T> openParentScopeOnSubscribe(Observation parentObservation, Flux<T> source) {
        if (parentObservation == null) {
            return source;
        }
        return new Flux<>() {
            @Override
            public void subscribe(CoreSubscriber<? super T> actual) {
                try (Observation.Scope ignored = parentObservation.openScope()) {
                    source.subscribe(actual);
                }
            }
        };
    }

    /** HALF_OPEN 试探判定（簇⑥ 批4 SLA）：已过 bypass 判定而失败数仍达阈 = 窗口结束后的试探请求 */
    private void recordHalfOpenProbeIfDue() {
        if (consecutiveFailures.get() >= failureThreshold) {
            metrics.recordCircuitHalfOpened();
        }
    }

    /**
     * 跨厂商转发屏障（2026-08-05 E2E 缺陷实证修正）：流入的 Prompt 携带主模型
     * options（ChatClient 装配期经路由模型 getOptions() 注入），备用
     * OpenAiChatModel.createRequest 对 prompt.getOptions() 强转 + 非空断言
     * （源码核验）。v2.19 簇③ D1 后主模型亦为 OpenAiChatOptions（类型同构，
     * 不再 ClassCastException），但凭证/端点/模型名仍属主模型——转发必须重建
     * Prompt 换入备用模型自身 options（含备用 baseUrl/apiKey/model，且保留备用
     * 自身的 include_usage 等选项）。代价：请求级自定义 options 转发时丢弃——
     * 当前链路无此调用方，已知取舍。
     */
    private Prompt retargetToFallback(Prompt prompt) {
        return new Prompt(prompt.getInstructions(), fallback.getOptions());
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
        if (failures > failureThreshold) {
            // 超过阈值只可能来自 HALF_OPEN 试探失败（OPEN 态主模型零触达）
            openUntilEpochMs.set(clock.millis() + openSeconds * 1000);
            metrics.recordCircuitOpened();
            log.warn("HALF_OPEN 试探失败（主模型累计失败 {} 次），熔断重开 OPEN {}s。最近原因: {}",
                failures, openSeconds, cause.getMessage());
        } else if (failures == failureThreshold) {
            openUntilEpochMs.set(clock.millis() + openSeconds * 1000);
            metrics.recordCircuitOpened();
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
