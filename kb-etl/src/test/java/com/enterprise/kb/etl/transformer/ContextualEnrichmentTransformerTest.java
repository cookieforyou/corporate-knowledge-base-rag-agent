package com.enterprise.kb.etl.transformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContextualEnrichmentTransformer 单测（簇④ A4，设计 9.5）
 */
class ContextualEnrichmentTransformerTest {

    private static final String CONTEXT = "该片段位于产品手册定价章节，说明企业版价格构成。";

    private ChatModel stubModel(String reply) {
        ChatModel stub = mock(ChatModel.class);
        when(stub.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))));
        return stub;
    }

    private static Document chunk(String text, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ContextualEnrichmentTransformer.DOC_EXCERPT_KEY, "文档标题：产品手册\n概要正文");
        meta.putAll(extraMeta);
        return Document.builder().text(text).metadata(meta).build();
    }

    @Test
    void blankApiKey_failsFastWithDashScopeHint() {
        // v2.78：语境增强挂辅助族，密钥缺失快失败报 DASHSCOPE_API_KEY（经 public 主构造器触发）
        assertThatCode(() -> new ContextualEnrichmentTransformer(
                "  ", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-flash", 2000, 8))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Test
    void enrichesChunk_withPrefixAndOriginalTextMetadata() {
        var transformer = new ContextualEnrichmentTransformer(stubModel(CONTEXT), 2000);
        String original = "企业版定价为每年十万元，包含全部功能模块。".repeat(3);

        List<Document> out = transformer.apply(List.of(chunk(original, Map.of())));

        Document enriched = out.get(0);
        assertThat(enriched.getText())
            .startsWith(ContextualEnrichmentTransformer.ENRICHMENT_PREFIX + CONTEXT)
            .endsWith(original);
        assertThat(enriched.getMetadata())
            .containsEntry(ContextualEnrichmentTransformer.ORIGINAL_TEXT_KEY, original)
            .doesNotContainKey(ContextualEnrichmentTransformer.DOC_EXCERPT_KEY);
    }

    @Test
    void imageChunk_skipped_andExcerptStripped() {
        var transformer = new ContextualEnrichmentTransformer(stubModel(CONTEXT), 2000);

        List<Document> out = transformer.apply(List.of(
            chunk("<img src=\"arch.png\">", Map.of("chunk_type", "IMAGE"))));

        assertThat(out.get(0).getText()).isEqualTo("<img src=\"arch.png\">");
        assertThat(out.get(0).getMetadata())
            .doesNotContainKey(ContextualEnrichmentTransformer.DOC_EXCERPT_KEY)
            .doesNotContainKey(ContextualEnrichmentTransformer.ORIGINAL_TEXT_KEY);
    }

    @Test
    void shortChunk_skipped() {
        var transformer = new ContextualEnrichmentTransformer(stubModel(CONTEXT), 2000);

        List<Document> out = transformer.apply(List.of(chunk("太短了", Map.of())));

        assertThat(out.get(0).getText()).isEqualTo("太短了");
        assertThat(out.get(0).getMetadata())
            .doesNotContainKey(ContextualEnrichmentTransformer.ORIGINAL_TEXT_KEY);
    }

    @Test
    void llmFailure_passesThroughWithoutBlocking() {
        ChatModel failing = mock(ChatModel.class);
        when(failing.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));
        var transformer = new ContextualEnrichmentTransformer(failing, 2000);
        String original = "足够长的正文内容，用于触发增强调用。".repeat(3);

        assertThatCode(() -> {
            List<Document> out = transformer.apply(List.of(chunk(original, Map.of())));
            // 失败原样放行：文本不变，概要键同样被清理
            assertThat(out.get(0).getText()).isEqualTo(original);
            assertThat(out.get(0).getMetadata())
                .doesNotContainKey(ContextualEnrichmentTransformer.DOC_EXCERPT_KEY);
        }).doesNotThrowAnyException();
    }

    @Test
    void blankLlmReply_passesThrough() {
        var transformer = new ContextualEnrichmentTransformer(stubModel("   "), 2000);
        String original = "足够长的正文内容，用于触发增强调用。".repeat(3);

        List<Document> out = transformer.apply(List.of(chunk(original, Map.of())));

        assertThat(out.get(0).getText()).isEqualTo(original);
        assertThat(out.get(0).getMetadata())
            .doesNotContainKey(ContextualEnrichmentTransformer.ORIGINAL_TEXT_KEY);
    }

    /**
     * 并发实证（2026-08-12 串行→有界并发优化）：3 个可增强 chunk + 3 方 Phaser 闸门——
     * 每个 LLM 桩调用在 phaser 上等待其余两路到齐才放行；若仍串行执行，首路永远等不到
     * 第二路 → 超时失败。能穿过闸门即证明 ≥3 路在飞，并发真实存在。
     */
    @Test
    void llmCallsRunConcurrently() {
        Phaser gate = new Phaser(3);
        ChatModel barrierModel = mock(ChatModel.class);
        when(barrierModel.call(any(Prompt.class))).thenAnswer(inv -> {
            gate.arriveAndAwaitAdvance();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(CONTEXT))));
        });
        var transformer = new ContextualEnrichmentTransformer(barrierModel, 2000, 3);
        String original = "足够长的正文内容，用于触发增强调用。".repeat(3);

        List<Document> out = assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> transformer.apply(List.of(
                chunk(original + "A", Map.of()),
                chunk(original + "B", Map.of()),
                chunk(original + "C", Map.of()))));

        assertThat(out).hasSize(3);
        assertThat(out).allSatisfy(d ->
            assertThat(d.getText()).startsWith(ContextualEnrichmentTransformer.ENRICHMENT_PREFIX));
    }

    /**
     * 并发上限纪律：并发度 2、6 个可增强 chunk——在飞数任何时刻不得超过 2
     * （虚拟线程无界，信号量是唯一闸门；桩内小睡制造重叠窗口）。
     */
    @Test
    void inFlightCallsNeverExceedConcurrencyLimit() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        ChatModel trackingModel = mock(ChatModel.class);
        when(trackingModel.call(any(Prompt.class))).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxSeen.accumulateAndGet(now, Math::max);
            Thread.sleep(30);
            inFlight.decrementAndGet();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(CONTEXT))));
        });
        var transformer = new ContextualEnrichmentTransformer(trackingModel, 2000, 2);
        String original = "足够长的正文内容，用于触发增强调用。".repeat(3);
        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chunks.add(chunk(original + i, Map.of()));
        }

        List<Document> out = transformer.apply(chunks);

        assertThat(out).hasSize(6);
        assertThat(maxSeen.get()).isLessThanOrEqualTo(2);
    }

    /** 混合批次保序：增强/跳过/失败三类交错并发处理后，输出与输入下标逐位对齐 */
    @Test
    void mixedBatch_preservesInputOrder() {
        ChatModel selective = mock(ChatModel.class);
        when(selective.call(any(Prompt.class)))
            .thenAnswer(inv -> {
                Prompt p = inv.getArgument(0);
                if (p.getContents().toString().contains("会失败的")) {
                    throw new RuntimeException("模拟失败");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage(CONTEXT))));
            });
        var transformer = new ContextualEnrichmentTransformer(selective, 2000, 4);
        String ok = "正常增强的正文内容，足够长度。".repeat(3);

        List<Document> out = transformer.apply(List.of(
            chunk(ok + "0", Map.of()),                                   // 增强
            chunk("太短", Map.of()),                                      // 跳过
            chunk("会失败的正文内容，足够长度。".repeat(3), Map.of()),       // 失败放行
            chunk(ok + "1", Map.of())));                                  // 增强

        assertThat(out).hasSize(4);
        assertThat(out.get(0).getText()).startsWith(ContextualEnrichmentTransformer.ENRICHMENT_PREFIX);
        assertThat(out.get(1).getText()).isEqualTo("太短");
        assertThat(out.get(2).getText()).contains("会失败的正文内容");
        assertThat(out.get(2).getText()).doesNotStartWith(ContextualEnrichmentTransformer.ENRICHMENT_PREFIX);
        assertThat(out.get(3).getText()).startsWith(ContextualEnrichmentTransformer.ENRICHMENT_PREFIX);
        assertThat(out.get(3).getText()).endsWith(ok + "1");
    }
}
