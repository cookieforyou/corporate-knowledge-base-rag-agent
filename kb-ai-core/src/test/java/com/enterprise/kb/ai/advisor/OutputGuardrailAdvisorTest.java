package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 输出安全护栏测试（3.6）—— 同步 after() 拦截 + 流式聚合后验 + 护栏命中计数（簇⑤ B2 S3）
 *
 * <p>v2.41/T2：词表切结构化加载（双源合并），测试词表经 CSV 兼容源注入占位词；
 * bundled 基线输出词表随 jar 发布并在构造时并入（不影响占位词断言语义）。
 */
class OutputGuardrailAdvisorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutputGuardrailAdvisor advisor =
        new OutputGuardrailAdvisor("", "competitor_x,competitor_y", new AiBusinessMetrics(meterRegistry));
    private final AdvisorChain chain = mock(AdvisorChain.class);

    private ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
            .context(Map.of("trace_start_ms", 1L))
            .build();
    }

    // ── 同步路径 after() ──

    @Test
    void blacklistedOutputReplacedWithContextPreserved() {
        ChatClientResponse result = advisor.after(response("推荐使用 competitor_x 的产品"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(result.context()).containsEntry("trace_start_ms", 1L);
    }

    @Test
    void cleanOutputPassesThroughUnchanged() {
        ChatClientResponse original = response("增值税发票是……");

        assertThat(advisor.after(original, chain)).isSameAs(original);
    }

    @Test
    void emptyResponsePassesThroughSafely() {
        ChatClientResponse empty = ChatClientResponse.builder()
            .context(Map.of("trace_start_ms", 1L))
            .build();

        assertThat(advisor.after(empty, chain)).isSameAs(empty);
    }

    // ── 流式路径 adviseStream() 聚合后验 ──

    private Flux<ChatClientResponse> streamThrough(List<ChatClientResponse> chunks) {
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of());
        when(streamChain.nextStream(any())).thenReturn(Flux.fromIterable(chunks));
        return advisor.adviseStream(request, streamChain);
    }

    @Test
    void cleanStreamReEmitsAllChunksInOrder() {
        List<ChatClientResponse> chunks = List.of(response("增值"), response("税发"), response("票"));

        List<String> texts = streamThrough(chunks)
            .map(r -> r.chatResponse().getResult().getOutput().getText())
            .collectList()
            .block();

        assertThat(texts).containsExactly("增值", "税发", "票");
    }

    @Test
    void violationSplitAcrossChunksReplacedBySingleSafeResponse() {
        // 敏感词跨块拆分——逐块检查必然漏检，聚合后验才能命中
        List<ChatClientResponse> chunks = List.of(response("推荐 compet"), response("itor_x 产品"));

        List<ChatClientResponse> results = streamThrough(chunks).collectList().block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
    }

    @Test
    void blankCsvCompatStillPassesUnrelatedOutput() {
        // CSV 空 → 仅 bundled 基线输出词表生效；无关输出照常放行
        OutputGuardrailAdvisor open = new OutputGuardrailAdvisor("", "", new AiBusinessMetrics(meterRegistry));
        ChatClientResponse original = response("competitor_x");

        assertThat(open.after(original, chain)).isSameAs(original);
    }

    // ── 护栏命中计数（簇⑤ B2 S3）──

    @Test
    void syncReplacementIncrementsGuardrailCounter() {
        advisor.after(response("推荐使用 competitor_x 的产品"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    @Test
    void streamReplacementIncrementsGuardrailCounter() {
        streamThrough(List.of(response("推荐 compet"), response("itor_x 产品")))
            .collectList()
            .block();

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    @Test
    void cleanOutputLeavesGuardrailCounterUntouched() {
        advisor.after(response("增值税发票是……"), chain);
        streamThrough(List.of(response("增值"), response("税发"))).collectList().block();

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isZero();
    }
}
