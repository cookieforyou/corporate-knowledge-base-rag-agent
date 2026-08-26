package com.enterprise.kb.etl.pipeline.graph;

import com.enterprise.kb.etl.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 实体关系抽取器（簇④ 5.1）：单 chunk（窗口语境）→ 结构化实体/关系。
 *
 * <p>模型手工装配（同 {@code ContextualEnrichmentTransformer#buildContextModel} 先例，
 * kb-etl 不依赖 kb-ai-core）——百炼 qwen3.7-plus 低价档结构化调用，
 * <b>enable_thinking=false 显式钉死</b>（坑位⑮：qwen 商业版默认开思考，
 * 单调用 20-60s 不可接受）；温度 0 求结构稳定。
 *
 * <p>失败语义：单 chunk 抽取异常返回 {@code null}，由服务侧计数跳过
 * （单点失败不击穿文档级抽取）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class EntityExtractor {

    private final ChatClient chatClient;

    @Autowired
    public EntityExtractor(GraphExtractionProperties properties) {
        this(buildExtractionModel(properties));
    }

    /** 测试入口：注入桩 ChatModel */
    EntityExtractor(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    private static ChatModel buildExtractionModel(GraphExtractionProperties properties) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "rag.graph.enabled=true 但抽取模型密钥未配置（rag.graph.extraction.api-key）——图谱抽取不可用");
        }
        log.info("图谱抽取模型装配: model={}, baseUrl={}", properties.getModel(), properties.getBaseUrl());
        return OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .maxTokens(properties.getMaxTokens())
                // 坑位⑮：qwen 商业版默认开思考——结构化抽取显式关闭
                .extraBody(Map.of("enable_thinking", false))
                .build())
            .build();
    }

    /**
     * 抽取单 chunk 的实体与关系。
     *
     * @param previousText 相邻上文（可空）
     * @param chunkText    目标片段（已截断）
     * @param nextText     相邻下文（可空）
     * @return 结构化抽取结果；失败/空响应返回 {@code null}
     */
    public ExtractionResult extract(String previousText, String chunkText, String nextText) {
        String promptText = PromptTemplates.KG_EXTRACTION_PROMPT.formatted(
            blankIfNull(previousText), chunkText, blankIfNull(nextText));
        try {
            ExtractionResult result = chatClient.prompt()
                .user(promptText)
                .call()
                .entity(ExtractionResult.class);
            return normalize(result);
        } catch (Exception e) {
            log.warn("图谱抽取单 chunk 失败（跳过该片段不阻断）: {}", e.getMessage());
            return null;
        }
    }

    /** 容错归一：null 列表收敛为空列表，越界名称去首尾空白 */
    private static ExtractionResult normalize(ExtractionResult raw) {
        if (raw == null) {
            return new ExtractionResult(List.of(), List.of());
        }
        List<ExtractionResult.EntityExtraction> entities = raw.entities() == null
            ? List.of() : raw.entities();
        List<ExtractionResult.RelationExtraction> relations = raw.relations() == null
            ? List.of() : raw.relations();
        return new ExtractionResult(entities, relations);
    }

    private static String blankIfNull(String s) {
        return s == null ? "（无）" : s;
    }
}
