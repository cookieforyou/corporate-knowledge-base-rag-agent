package com.enterprise.kb.eval.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Judge 模型装配 —— LLM-as-Judge（设计文档 16.3）
 *
 * <p>被测链路为 DeepSeek V4（kb-ai-core chatClient），Judge 默认走百炼 qwen3.7-plus，
 * 构成**跨厂商评判**，规避 self-preference 偏差（16.3 隔离原则）。
 * 复用现有 DASHSCOPE_API_KEY，无需新增密钥。
 *
 * <p>装配形态：baseUrl/apiKey 经 {@link OpenAiChatOptions} 传入，由 Spring AI 统一
 * 构建同步与异步 OpenAI 客户端。注意**不可**只预建同步 client 经
 * {@code .openAiClient()} 传入——源码核验（OpenAiChatModel.Builder.build()）：
 * 异步 client 始终独立从 options 的 baseUrl/apiKey 构建，不继承预建的同步 client
 * 凭证，导致启动期 "At least one credential source must be specified"。
 */
@Configuration
public class JudgeModelConfig {

    @Bean
    public ChatClient judgeChatClient(EvalProperties props) {
        EvalProperties.Judge cfg = props.getJudge();
        // 快失败：密钥缺失直接报清晰错误，避免落入 OpenAI SDK 的晦涩 credential 异常
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "DASHSCOPE_API_KEY 未配置（eval.judge.api-key 为空）——Judge 模型不可用，无法评估");
        }

        ChatModel judgeModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .model(cfg.getModel())
                .temperature(cfg.getTemperature())
                .build())
            .build();

        return ChatClient.builder(judgeModel).build();
    }
}
