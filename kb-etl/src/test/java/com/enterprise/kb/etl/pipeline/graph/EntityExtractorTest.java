package com.enterprise.kb.etl.pipeline.graph;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EntityExtractor 单测（簇④）：结构化输出解析 + 失败隔离语义。
 */
class EntityExtractorTest {

    private ChatModel stubModel(String reply) {
        ChatModel stub = mock(ChatModel.class);
        when(stub.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(reply)))));
        // ChatClient 内部经 getOptions().mutate() 合并请求级选项——桩须供非空 options
        when(stub.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        return stub;
    }

    @Test
    void parsesStructuredExtractionResult() {
        String json = """
            {
              "entities": [
                {"name": "Alpha Corp", "type": "ORG", "description": "发布 X1 的制造企业"},
                {"name": "X1", "type": "PRODUCT", "description": "2024 年发布的旗舰产品"}
              ],
              "relations": [
                {"sourceName": "Alpha Corp", "targetName": "X1",
                 "relationType": "PRODUCED_BY", "description": "X1 由 Alpha Corp 发布"}
              ]
            }
            """;
        EntityExtractor extractor = new EntityExtractor(stubModel(json));

        ExtractionResult result = extractor.extract("（无）", "Alpha Corp 发布了产品 X1。", "（无）");

        assertThat(result).isNotNull();
        assertThat(result.entities()).hasSize(2);
        assertThat(result.entities().get(0).name()).isEqualTo("Alpha Corp");
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().get(0).relationType()).isEqualTo("PRODUCED_BY");
    }

    @Test
    void nullListsNormalizeToEmpty() {
        EntityExtractor extractor = new EntityExtractor(stubModel("""
            {"entities": null, "relations": null}
            """));

        ExtractionResult result = extractor.extract(null, "无实体片段。", null);

        assertThat(result).isNotNull();
        assertThat(result.entities()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void extractionFailureReturnsNullWithoutThrowing() {
        ChatModel failing = mock(ChatModel.class);
        when(failing.call(any(Prompt.class))).thenThrow(new RuntimeException("模拟供应商故障"));
        EntityExtractor extractor = new EntityExtractor(failing);

        assertThat(extractor.extract(null, "任意片段文本内容。", null)).isNull();
    }
}
