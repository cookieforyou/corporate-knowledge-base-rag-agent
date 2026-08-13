package com.enterprise.kb.ai.config;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容捕获 convention 单测（簇①）——gen_ai.prompt/gen_ai.completion 高基数 KeyValue 契约
 */
class ContentCapturingChatModelObservationConventionTest {

    private final ContentCapturingChatModelObservationConvention convention =
        new ContentCapturingChatModelObservationConvention();

    private ChatModelObservationContext context(Prompt prompt, ChatResponse response) {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
            .prompt(prompt)
            .provider("test-provider")
            .streaming(false)
            .build();
        if (response != null) {
            context.setResponse(response);
        }
        return context;
    }

    private static String valueOf(KeyValues keyValues, String key) {
        for (KeyValue kv : keyValues) {
            if (kv.getKey().equals(key)) {
                return kv.getValue();
            }
        }
        return null;
    }

    @Test
    void promptAndCompletionCapturedAsContractKeyValues() {
        Prompt prompt = new Prompt(List.of(
            new SystemMessage("你是企业知识助手"),
            new UserMessage("发票税率是多少？")));
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().build();
        ChatResponse response = new ChatResponse(
            List.of(new Generation(new AssistantMessage("税率是 13%。"))), metadata);

        KeyValues keyValues = convention.getHighCardinalityKeyValues(context(prompt, response));

        assertThat(valueOf(keyValues, "gen_ai.prompt"))
            .isEqualTo("[system] 你是企业知识助手\n[user] 发票税率是多少？\n");
        assertThat(valueOf(keyValues, "gen_ai.completion")).isEqualTo("税率是 13%。");
    }

    @Test
    void baselineKeysRetained() {
        Prompt prompt = new Prompt(List.of(new UserMessage("问题")));
        KeyValues keyValues = convention.getHighCardinalityKeyValues(context(prompt, null));

        // 父类基线键（token/模型等请求级属性）不丢失——仅追加内容键
        assertThat(keyValues).isNotEmpty();
        assertThat(valueOf(keyValues, "gen_ai.completion")).isNull();
    }

    @Test
    void oversizedContentTruncated() {
        String big = "长".repeat(ContentCapturingChatModelObservationConvention.MAX_CONTENT_CHARS + 100);
        Prompt prompt = new Prompt(List.of(new UserMessage(big)));

        KeyValues keyValues = convention.getHighCardinalityKeyValues(context(prompt, null));

        String captured = valueOf(keyValues, "gen_ai.prompt");
        assertThat(captured).endsWith("...[truncated]");
        assertThat(captured).hasSize(
            ContentCapturingChatModelObservationConvention.MAX_CONTENT_CHARS + "...[truncated]".length());
    }
}
