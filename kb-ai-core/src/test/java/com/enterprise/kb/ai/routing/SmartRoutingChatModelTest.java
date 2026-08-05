package com.enterprise.kb.ai.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * 流式错误接管、options 委托
 */
class SmartRoutingChatModelTest {

    private ChatModel primary;
    private ChatModel fallback;
    private MutableClock clock;
    private SmartRoutingChatModel router;

    private final Prompt prompt = new Prompt("什么是增值税发票？");

    @BeforeEach
    void setUp() {
        primary = mock(ChatModel.class);
        fallback = mock(ChatModel.class);
        clock = new MutableClock();
        router = new SmartRoutingChatModel(primary, fallback, 3, 30, clock);
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
        when(fallback.call(prompt)).thenReturn(response("备用模型回答"));

        assertThat(textOf(router.call(prompt))).isEqualTo("备用模型回答");
    }

    @Test
    void circuitOpensAfterThresholdAndBypassesPrimary() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(prompt)).thenReturn(response("备用"));

        // 阈值 3：前三次均尝试主模型（失败即切备用），第三次触发熔断
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);
        // OPEN 态：第四次直发备用，主模型零触达
        router.call(prompt);

        verify(primary, times(3)).call(prompt);
        verify(fallback, times(4)).call(prompt);
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(prompt)).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        clock.advanceSeconds(31);                  // 窗口结束 → HALF_OPEN
        // 重 stub 抛异常的方法须用 doReturn（when() 重 stub 会真实触发原异常）
        doReturn(response("主模型恢复")).when(primary).call(prompt);

        assertThat(textOf(router.call(prompt))).isEqualTo("主模型恢复");  // 试探成功
        // 计数清零，后续稳定走主模型
        assertThat(textOf(router.call(prompt))).isEqualTo("主模型恢复");
        verify(fallback, times(3)).call(prompt);   // 仅熔断期三次
    }

    @Test
    void halfOpenProbeFailureReopensCircuitImmediately() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(prompt)).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        clock.advanceSeconds(31);
        router.call(prompt);                       // HALF_OPEN 试探失败 → 立即重开
        router.call(prompt);                       // 应直发备用，主模型零触达

        verify(primary, times(4)).call(prompt);    // 3 次熔断前 + 1 次试探
        verify(fallback, times(5)).call(prompt);
    }

    @Test
    void primarySuccessResetsFailureCount() {
        when(primary.call(prompt))
            .thenThrow(new RuntimeException("once"))
            .thenReturn(response("恢复"));
        router.call(prompt);                       // 失败 1/3
        router.call(prompt);                       // 成功，计数清零
        when(primary.call(prompt)).thenThrow(new RuntimeException("again"));
        when(fallback.call(prompt)).thenReturn(response("备用"));
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
        when(fallback.stream(prompt)).thenReturn(Flux.just(response("备用完整回答")));

        List<String> texts = router.stream(prompt).map(SmartRoutingChatModelTest::textOf)
            .collectList().block();

        assertThat(texts).containsExactly("备用完整回答");
    }

    @Test
    void streamCircuitOpenDirectsToFallback() {
        when(primary.call(prompt)).thenThrow(new RuntimeException("boom"));
        when(fallback.call(prompt)).thenReturn(response("备用"));
        router.call(prompt);
        router.call(prompt);
        router.call(prompt);                       // 熔断 OPEN
        when(fallback.stream(prompt)).thenReturn(Flux.just(response("备用流")));

        router.stream(prompt).collectList().block();

        verify(primary, never()).stream(any(Prompt.class));
    }

    // ── options 委托 ──

    @Test
    void optionsDelegateToPrimary() {
        ChatOptions options = mock(ChatOptions.class);
        when(primary.getOptions()).thenReturn(options);

        assertThat(router.getOptions()).isSameAs(options);
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
