package com.enterprise.kb.etl.transformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
}
