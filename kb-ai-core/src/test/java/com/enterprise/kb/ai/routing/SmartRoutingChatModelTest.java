package com.enterprise.kb.ai.routing;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 多模型智能路由测试（3.2）—— 主备切换、熔断三态（CLOSED/OPEN/HALF_OPEN）、
 * 流式错误接管、options 委托；簇⑥ 批4 扩充：双供应商 SLA 计数 +
 * 流式 trace 父观测订阅期传播（POST span 合树修复机制）
 */
class SmartRoutingChatModelTest {

    private ChatModel primary;
    private ChatModel fallback;
    private MutableClock clock;
    private SimpleMeterRegistry meterRegistry;
    private SmartRoutingChatModel router;

    private final Prompt prompt = new Prompt("什么是增值税发票？");

    @BeforeEach
    void setUp() {
        primary = mock(ChatModel.class);
        fallback = mock(ChatModel.class);
        clock = new MutableClock();
        meterRegistry = new SimpleMeterRegistry();
        router = new SmartRoutingChatModel(primary, fallback, 3, 30, clock,
            new AiBusinessMetrics(meterRegistry));
    }

    private double counterValue(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static String textOf(ChatResponse r) {
        return r.getResult().getOutput().getText();
    }

    // ── 同步路径 ──

    @Test
    void primaryServesWhenHealthy() {
        when(primary.call(prompt)).thenReturn(response("主模型回答"));

        assertThat(textOf(router.call(prompt))).isEqualTo("主模型回答");
        verifyNoInteractions(fallback);
    }

    @Test
    void fallbackServesImmediatelyOnPrimaryFailure() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("connection refused"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用模型回答"));

        assertThat(textOf(router.call(prompt))).isEqualTo("备用模型回答");
    }

    @Test
    void circuitOpensAfterThresholdAndBypassesPrimary() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));

        // 阈值 3：前三次均尝试主模型（失败即切备用），第三次触发熔断
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);
        // OPEN 态：第四次直发备用，主模型零触达
        router.call(prompt);

        verify(primary, times(3)).call(prompt);
        verify(fallback, times(4)).call(any(Prompt.class));
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        clock.advanceSeconds(31);                  // 窗口结束 → HALF_OPEN
        // 重 stub 抛异常的方法须用 doReturn（when() 重 stub 会真实触发原异常）
        doReturn(response("主模型恢复")).when(primary).call(prompt);

        assertThat(textOf(router.call(prompt))).isEqualTo("主模型恢复");  // 试探成功
        // 计数清零，后续稳定走主模型
        assertThat(textOf(router.call(prompt))).isEqualTo("主模型恢复");
        verify(fallback, times(3)).call(any(Prompt.class));   // 仅熔断期三次
    }

    @Test
    void halfOpenProbeFailureReopensCircuitImmediately() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        clock.advanceSeconds(31);
        router.call(prompt);                       // HALF_OPEN 试探失败 → 立即重开
        router.call(prompt);                       // 应直发备用，主模型零触达

        verify(primary, times(4)).call(prompt);    // 3 次熔断前 + 1 次试探
        verify(fallback, times(5)).call(any(Prompt.class));
    }

    @Test
    void primarySuccessResetsFailureCount() {
        when(primary.call(prompt))
            .thenThrow(new RuntimeException("once"))
            .thenReturn(response("恢复"));
        router.call(prompt);                       // 失败 1/3
        router.call(prompt);                       // 成功，计数清零
        when(primary.call(prompt)).thenThrow(new RuntimeException("again"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));
        router.call(prompt);                       // 重新计数 1/3，未熔断
        router.call(prompt);                       // 2/3

        // 两次新失败后仍 CLOSED——证明计数曾被成功清零
        verify(primary, times(4)).call(prompt);
    }

    // ── 流式路径 ──

    @Test
    void streamPrimaryHealthyPassesThrough() {
        when(primary.stream(prompt)).thenReturn(Flux.just(response("增"), response("值")));

        List<String> texts = router.stream(prompt).map(SmartRoutingChatModelTest::textOf)
            .collectList().block();

        assertThat(texts).containsExactly("增", "值");
        verifyNoInteractions(fallback);
    }

    @Test
    void streamPrimaryErrorFallsBackToFullStream() {
        when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("boom")));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("备用完整回答")));

        List<String> texts = router.stream(prompt).map(SmartRoutingChatModelTest::textOf)
            .collectList().block();

        assertThat(texts).containsExactly("备用完整回答");
    }

    @Test
    void streamCircuitOpenDirectsToFallback() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("备用流")));

        router.stream(prompt).collectList().block();

        verify(primary, never()).stream(any(Prompt.class));
    }

    // ── options 委托与跨厂商转发屏障 ──

    @Test
    void optionsDelegateToPrimary() {
        ChatOptions options = mock(ChatOptions.class);
        when(primary.getOptions()).thenReturn(options);

        assertThat(router.getOptions()).isSameAs(options);
    }

    // ── 双供应商 SLA 计数（簇⑥ 批4） ──

    @Test
    void slaCountersTrackFailoverAndCircuitTransitions() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));

        router.call(prompt);                       // 失败 1/3，失败即切备用
        router.call(prompt);                       // 失败 2/3
        router.call(prompt);                       // 失败 3/3 → OPEN
        router.call(prompt);                       // OPEN 直发备用

        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(4);
        assertThat(counterValue("rag.routing.circuit.opened")).isEqualTo(1);
        assertThat(counterValue("rag.routing.circuit.half-opened")).isZero();

        clock.advanceSeconds(31);                  // 窗口结束 → HALF_OPEN 试探
        router.call(prompt);                       // 试探失败 → 熔断重开

        assertThat(counterValue("rag.routing.circuit.half-opened")).isEqualTo(1);
        assertThat(counterValue("rag.routing.circuit.opened")).isEqualTo(2);
        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(5);

        clock.advanceSeconds(31);
        doReturn(response("主模型恢复")).when(primary).call(prompt);
        router.call(prompt);                       // 试探成功 → 闭合，计数清零

        assertThat(counterValue("rag.routing.circuit.half-opened")).isEqualTo(2);
        assertThat(counterValue("rag.routing.circuit.opened")).isEqualTo(2);
        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(5);
    }

    @Test
    void streamCircuitOpenCountsFallbackInvoked() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(any(Prompt.class))).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // OPEN（三次失败即切各计一次）
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("备用流")));

        router.stream(prompt).collectList().block();

        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(4);
        verify(primary, never()).stream(any(Prompt.class));
    }

    @Test
    void streamPrimaryErrorCountsFallbackInvokedOnce() {
        when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("boom")));
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.just(response("备用")));

        router.stream(prompt).collectList().block();

        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(1);
        assertThat(counterValue("rag.routing.circuit.opened")).isZero();
    }

    // ── 流式 trace 父观测订阅期传播（簇⑥ 批4 残余修复机制验证） ──

    @Test
    void streamOpensParentObservationScopeOnSubscribeThread() {
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        Observation parent = Observation.createNotStarted("chat_client", observationRegistry).start();
        // 复现生产形态：父观测只在 Reactor Context，订阅线程 ThreadLocal 为空
        assertThat(observationRegistry.getCurrentObservation()).isNull();

        // 模拟 OpenAiChatModel.internalStream 形态：Flux.create 消费者在订阅瞬间执行
        // （真实链路在该点 enqueue HTTP 请求，OkHttp dispatcher 捕获此刻线程上下文）
        AtomicReference<Observation> capturedAtEnqueuePoint = new AtomicReference<>();
        when(primary.stream(prompt)).thenReturn(Flux.create(sink -> {
            capturedAtEnqueuePoint.set(observationRegistry.getCurrentObservation());
            sink.next(response("token"));
            sink.complete();
        }));

        router.stream(prompt)
            .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, parent))
            .collectList().block();

        assertThat(capturedAtEnqueuePoint.get()).isSameAs(parent);
        // 作用域随订阅结束关闭，不泄漏到订阅线程后续执行
        assertThat(observationRegistry.getCurrentObservation()).isNull();
    }

    @Test
    void streamFallbackResubscribeAlsoRestoresParentScope() {
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        Observation parent = Observation.createNotStarted("chat_client", observationRegistry).start();
        when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("boom")));
        AtomicReference<Observation> captured = new AtomicReference<>();
        when(fallback.stream(any(Prompt.class))).thenReturn(Flux.create(sink -> {
            captured.set(observationRegistry.getCurrentObservation());
            sink.next(response("备用"));
            sink.complete();
        }));

        router.stream(prompt)
            .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, parent))
            .collectList().block();

        assertThat(captured.get()).isSameAs(parent);
        assertThat(counterValue("rag.routing.fallback.invoked")).isEqualTo(1);
    }

    @Test
    void streamWithoutParentObservationPassesThrough() {
        when(primary.stream(prompt)).thenReturn(Flux.just(response("无观测环境")));

        List<String> texts = router.stream(prompt).map(SmartRoutingChatModelTest::textOf)
            .collectList().block();

        assertThat(texts).containsExactly("无观测环境");
    }

    @Test
    void fallbackReceivesPromptRetargetedWithItsOwnOptions() {
        // E2E 缺陷回归：Prompt 携带主模型 options（跨厂商强转必炸），
        // 转发备用时必须换入备用模型自身 options
        ChatOptions primaryOptions = mock(ChatOptions.class);
        ChatOptions fallbackOptions = mock(ChatOptions.class);
        when(fallback.getOptions()).thenReturn(fallbackOptions);
        Prompt promptWithPrimaryOptions =
            new Prompt(List.of(new UserMessage("什么是增值税发票？")), primaryOptions);
        when(primary.call(promptWithPrimaryOptions)).thenThrow(new RuntimeException("boom"));

        router.call(promptWithPrimaryOptions);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(fallback).call(captor.capture());
        assertThat(captor.getValue().getOptions()).isSameAs(fallbackOptions);
        assertThat(captor.getValue().getInstructions()).hasSize(1);
    }

    /** 可推进的测试时钟 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-05T00:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
