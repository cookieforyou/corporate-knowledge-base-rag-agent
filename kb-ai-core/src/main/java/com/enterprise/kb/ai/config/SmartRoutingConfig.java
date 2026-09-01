package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.routing.SmartRoutingChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
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
 * <p>主模型为手工装配的 {@code deepSeekChatModel}（DeepSeek OpenAI 兼容端点）；
 * 备用模型为百炼 qwen3.8-flash（OpenAI 兼容端点，跨厂商容灾，复用 DASHSCOPE_API_KEY）——
 * 装配形态与 kb-eval JudgeModelConfig 同款实证先例：baseUrl/apiKey 必须经
 * {@link OpenAiChatOptions} 传入（异步 client 不继承预建同步 client 凭证，
 * 见 CLAUDE.md 坑位①）。
 *
 * <p><b>主模型手工装配缘由（v2.19，簇③ D1 流式 token 计账）</b>：deepseek starter
 * 的 {@code DeepSeekApi.ChatCompletionRequest} record 无 {@code stream_options} 字段、
 * {@code DeepSeekChatOptions} 无 streamUsage（2.0.0 源码核验）——无法开启
 * include_usage，流式消耗系统性漏算。改以 {@link OpenAiChatModel} 指向 DeepSeek
 * OpenAI 兼容端点（base-url 与 starter 默认值一致），开
 * {@code stream_options.include_usage} 使末块携带 usage（TokenBudgetAdvisor/审计
 * 末块回写链路零改动即生效）。Bean 名保持 {@code deepSeekChatModel}，所有
 * @Qualifier 注入点零感知；spring.ai.deepseek.* 键经 @Value 消费，键名不变。
 *
 * <p><b>starter 让位机制（实证修正）</b>：曾试「同名用户 Bean 让位自动装配」——
 * ApplicationContextRunner 实证 Boot 4.1 直接抛 BeanDefinitionOverrideException
 * （同名让位不成立）。正解：starter 自动配置类挂
 * {@code @ConditionalOnProperty(name="spring.ai.model.chat", havingValue="deepseek")}，
 * application-ai.yml 将该键置 {@code none} 令其整体不装配。若回写 deepseek，
 * 同名冲突启动失败——DeepSeekModelOverrideWiringTest 双向钉死。
 *
 * <p>{@code rag.routing.fallback.enabled=false} 时备用 Bean 不装配，路由 Bean
 * 直接透传主模型——降级为单模型形态，链路零变化。
 */
@Configuration
public class SmartRoutingConfig {

    /**
     * 主模型（DeepSeek OpenAI 兼容端点）——同名 Bean 替代 deepseek starter 自动装配
     * （D1，见类注）。密钥缺失快失败：与备用/Judge 同策。
     */
    @Bean("deepSeekChatModel")
    public ChatModel deepSeekChatModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            @Value("${spring.ai.deepseek.api-key:}") String apiKey,
            @Value("${spring.ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${spring.ai.deepseek.chat.model:deepseek-v4-flash}") String model,
            @Value("${spring.ai.deepseek.chat.temperature:0.1}") Double temperature,
            @Value("${spring.ai.deepseek.chat.max-tokens:4096}") Integer maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置——主模型不可用");
        }
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                // maxTokens 序列化 wire 字段为 max_tokens（官方 SDK 映射，源码核验），
                // 与 deepseek starter 时代行为一致——单次请求 token 上限硬约束不变
                .maxTokens(maxTokens)
                // D1：流式末块携带 usage（stream_options.include_usage）——DeepSeek 官方
                // 文档确认支持；TokenBudgetAdvisor/审计经末块 usage 回写，配额漏算修复
                .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                    .includeUsage(true)
                    .build())
                .build())
            // 手工装配必须显式挂 ObservationRegistry（坑位⑯）
            .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
        // 内容捕获 convention（簇①）：仅开关开启时注册，经 ObjectProvider 条件消费
        observationConventionProvider.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    /**
     * 备用模型（qwen3.8-flash，百炼 OpenAI 兼容端点）。
     * 密钥缺失快失败：与 JudgeModelConfig 同策，避免落入 OpenAI SDK 晦涩凭证异常。
     */
    @Bean
    @ConditionalOnProperty(name = "rag.routing.fallback.enabled", havingValue = "true", matchIfMissing = true)
    public ChatModel fallbackChatModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            @Value("${rag.routing.fallback.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${rag.routing.fallback.api-key:}") String apiKey,
            @Value("${rag.routing.fallback.model:qwen3.8-flash}") String model,
            @Value("${rag.routing.fallback.temperature:0.1}") Double temperature,
            @Value("${rag.routing.fallback.enable-thinking:false}") boolean enableThinking) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "DASHSCOPE_API_KEY 未配置——备用模型不可用。关闭路由请设 rag.routing.fallback.enabled=false");
        }
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                // qwen 商业版（3.5+ 各代）默认开思考模式（enable_thinking=true，官方文档实证）：
                // 每次调用先生成大量思维链——E2E 实测单调用 20-60s，故障接管场景不可接受。
                // 默认显式关闭；extraBody 经 createRequest 透传至请求体顶层（源码核验）
                .extraBody(Map.of("enable_thinking", enableThinking))
                // D1：百炼 OpenAI 兼容端点同样支持 stream_options.include_usage（官方文档），
                // 故障接管期间流式计量不中断（转发经 retargetToFallback 换入本 options）
                .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                    .includeUsage(true)
                    .build())
                .build())
            // 手工装配的 ChatModel 必须显式挂 ObservationRegistry，否则模型调用无观测、
            // ChatModelCompletionObservationHandler 不打 Completion 日志（自动装配的
            // deepSeekChatModel 由 starter 注入，手工 builder 不继承——E2E 观测实证）
            .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
        observationConventionProvider.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
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
            @Value("${rag.routing.circuit.open-seconds:30}") long openSeconds,
            AiBusinessMetrics aiBusinessMetrics) {
        if (fallback == null) {
            return primary;
        }
        return new SmartRoutingChatModel(primary, fallback, failureThreshold, openSeconds, aiBusinessMetrics);
    }

    /**
     * 内容捕获 convention（Phase 4 簇①）——仅内容捕获开关开启时注册（与
     * {@code spring.ai.chat.observations.log-prompt} 同源，同一 env 开关
     * RAG_OBSERVABILITY_LOG_CONTENT 驱动）。把 prompt/completion 作为高基数 KeyValue
     * 写入 ChatModel observation，经 tracing 桥落 span attribute（gen_ai.prompt/
     * gen_ai.completion，Langfuse OTLP 映射契约），补 Spring AI 2.0 内容只进日志不进
     * span 的缺口。主/备手工模型经 ObjectProvider 条件消费（见两 Bean）。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.chat.observations.log-prompt", havingValue = "true")
    public ChatModelObservationConvention contentCapturingChatModelObservationConvention() {
        return new ContentCapturingChatModelObservationConvention();
    }
}
