package com.enterprise.kb.eval.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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
 * <p>API 形态：Spring AI 2.0 的 OpenAI 集成基于官方 OpenAI Java SDK
 * （OpenAIClient + OpenAIOkHttpClient），经 2.0.0 GA Javadoc 核验。
 */
@Configuration
public class JudgeModelConfig {

    @Bean
    public ChatClient judgeChatClient(EvalProperties props) {
        EvalProperties.Judge cfg = props.getJudge();
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(cfg.getApiKey() == null ? "" : cfg.getApiKey())
            .build();

        ChatModel judgeModel = OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .options(OpenAiChatOptions.builder()
                .model(cfg.getModel())
                .temperature(cfg.getTemperature())
                .build())
            .build();

        return ChatClient.builder(judgeModel).build();
    }
}
