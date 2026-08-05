package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.routing.SmartRoutingChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.lang.Nullable;

/**
 * 多模型路由装配（设计文档 11.2.2，任务 3.2）
 *
 * <p>主模型为 deepseek 自动装配的 {@code deepSeekChatModel}；备用模型为百炼
 * qwen3.7-plus（OpenAI 兼容端点，跨厂商容灾，复用 DASHSCOPE_API_KEY）——
 * 装配形态与 kb-eval JudgeModelConfig 同款实证先例：baseUrl/apiKey 必须经
 * {@link OpenAiChatOptions} 传入（异步 client 不继承预建同步 client 凭证，
 * 见 CLAUDE.md 坑位①）。
 *
 * <p>{@code rag.routing.fallback.enabled=false} 时备用 Bean 不装配，路由 Bean
 * 直接透传主模型——降级为单模型形态，链路零变化。
 */
@Configuration
public class SmartRoutingConfig {

    /**
     * 备用模型（qwen3.7-plus，百炼 OpenAI 兼容端点）。
     * 密钥缺失快失败：与 JudgeModelConfig 同策，避免落入 OpenAI SDK 晦涩凭证异常。
     */
    @Bean
    @ConditionalOnProperty(name = "rag.routing.fallback.enabled", havingValue = "true", matchIfMissing = true)
    public ChatModel fallbackChatModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            @Value("${rag.routing.fallback.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${rag.routing.fallback.api-key:}") String apiKey,
            @Value("${rag.routing.fallback.model:qwen3.7-plus}") String model,
            @Value("${rag.routing.fallback.temperature:0.1}") Double temperature,
            @Value("${rag.routing.fallback.enable-thinking:false}") boolean enableThinking) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "DASHSCOPE_API_KEY 未配置——备用模型不可用。关闭路由请设 rag.routing.fallback.enabled=false");
        }
        return OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                // qwen3.5/3.6/3.7 商业版默认开思考模式（enable_thinking=true，官方文档实证）：
                // 每次调用先生成大量思维链——E2E 实测单调用 20-60s，故障接管场景不可接受。
                // 默认显式关闭；extraBody 经 createRequest 透传至请求体顶层（源码核验）
                .extraBody(Map.of("enable_thinking", enableThinking))
                .build())
            // 手工装配的 ChatModel 必须显式挂 ObservationRegistry，否则模型调用无观测、
            // ChatModelCompletionObservationHandler 不打 Completion 日志（自动装配的
            // deepSeekChatModel 由 starter 注入，手工 builder 不继承——E2E 观测实证）
            .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
    }

    /**
     * 路由模型：主 + 备熔断切换，替代各 ChatClient 对 deepSeekChatModel 的直注。
     * 备用未装配（功能关闭）时透传主模型，单模型形态零行为变化。
     *
     * <p><b>@Primary 必要性（2026-08-05 启动失败实证）</b>：引入多 ChatModel Bean 后，
     * Spring AI {@code ChatClientAutoConfiguration#chatClientBuilder} 按类型裸注入
     * 单一 ChatModel（源码核验），三 Bean 歧义致启动失败——路由模型即应用级模型，
     * 标记 @Primary 统一消解（显式 @Qualifier 注入点不受影响）。
     */
    @Bean
    @Primary
    public ChatModel smartRoutingChatModel(
            @Qualifier("deepSeekChatModel") ChatModel primary,
            @Nullable @Qualifier("fallbackChatModel") ChatModel fallback,
            @Value("${rag.routing.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${rag.routing.circuit.open-seconds:30}") long openSeconds) {
        if (fallback == null) {
            return primary;
        }
        return new SmartRoutingChatModel(primary, fallback, failureThreshold, openSeconds);
    }
}
