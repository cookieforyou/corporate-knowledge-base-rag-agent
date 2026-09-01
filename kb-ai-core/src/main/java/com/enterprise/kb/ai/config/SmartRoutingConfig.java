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
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

import java.util.Map;
import org.springframework.context.annotation.Primary;

/**
 * 多模型路由装配（设计文档 11.2.2，任务 3.2）
 *
 * <p><b>主模型双形态开关（v2.77，模型层批B）</b>：{@code rag.routing.primary.provider}
 * = {@code glm}（缺省，智谱 GLM-5.3-Flash）| {@code deepseek}（回落形态）——两 Bean 互斥
 * 条件装配，经 {@code primaryChatModel} 桥透传给 {@link SmartRoutingChatModel}。
 * 切换只动一个环境变量（RAG_ROUTING_PRIMARY_PROVIDER），零代码改动。
 *
 * <ul>
 *   <li><b>GLM-5.3-Flash</b>：强制思考不可关（官方文档：{@code thinking.type} 仅支持
 *       enabled，传 disabled 直接报错）；{@code reasoning_effort} 支持 low/high/max
 *       （缺省 max，其余值报错）——经 OpenAiChatOptions 原生字段透传，空=不传=服务端
 *       默认 max，生产档位经冒烟定档。采样参数缺省按官方推荐 temperature 1.0。</li>
 *   <li><b>DeepSeek V4 Flash</b>：官方文档实证「思考模式默认打开且 effort 默认 high」
 *       「思考模式静默忽略 temperature/top_p 等采样参数（设值不报错不生效）」——
 *       历史形态（v2.76 前）主路一直以思考开 high 运行，temperature 0.1 从未生效。
 *       回落形态缺省显式关思考（{@code thinking-type: disabled}）：回落定位 = 快答
 *       形态，关思考后采样参数恢复支持（限制条款明确绑定思考模式）；如需保留思考
 *       置 {@code thinking-type: enabled} + {@code reasoning-effort: low|high|max}。</li>
 * </ul>
 *
 * <p>两载体均为手工装配的 OpenAI 兼容形态（OpenAiChatModel），装配形态与 kb-eval
 * JudgeModelConfig 同款实证先例：baseUrl/apiKey 必须经 {@link OpenAiChatOptions}
 * 传入（异步 client 不继承预建同步 client 凭证，见 CLAUDE.md 坑位①）。
 *
 * <p><b>主模型手工装配缘由（v2.19，簇③ D1 流式 token 计账）</b>：deepseek starter
 * 的 {@code DeepSeekApi.ChatCompletionRequest} record 无 {@code stream_options} 字段、
 * {@code DeepSeekChatOptions} 无 streamUsage（2.0.0 源码核验）——无法开启
 * include_usage，流式消耗系统性漏算。改以 {@link OpenAiChatModel} 指向 OpenAI 兼容
 * 端点，开 {@code stream_options.include_usage} 使末块携带 usage
 * （TokenBudgetAdvisor/审计末块回写链路零改动即生效）。
 *
 * <p><b>deepseek starter 退役（v2.77）</b>：主模型双形态均手工装配后，starter 依赖
 * 移除；v2.19 的「chat=none 让位机制」不再有让位对象，{@code spring.ai.model.chat:
 * none} 保留为防御位（防未来误引 chat starter 自动装配形成同名/双主模型冲突）。
 * 让位机制实证记录（Boot 4.1 同名 Bean 直接抛 BeanDefinitionOverrideException）
 * 留档 11.2.2 历史记注。
 *
 * <p>{@code rag.routing.fallback.enabled=false} 时备用 Bean 不装配，路由 Bean
 * 直接透传主模型——降级为单模型形态，链路零变化。
 */
@Configuration
public class SmartRoutingConfig {

    /**
     * 主模型（DeepSeek 形态）——provider=deepseek 时在场。
     * 密钥缺失快失败：与备用/Judge 同策。Bean 名沿用 {@code deepSeekChatModel}。
     */
    @Bean("deepSeekChatModel")
    @ConditionalOnProperty(name = "rag.routing.primary.provider", havingValue = "deepseek")
    public ChatModel deepSeekChatModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            @Value("${rag.routing.primary.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${rag.routing.primary.deepseek.api-key:}") String apiKey,
            @Value("${rag.routing.primary.deepseek.model:deepseek-v4-flash}") String model,
            @Value("${rag.routing.primary.deepseek.temperature:0.1}") Double temperature,
            @Value("${rag.routing.primary.deepseek.max-tokens:4096}") Integer maxTokens,
            @Value("${rag.routing.primary.deepseek.thinking-type:disabled}") String thinkingType,
            @Value("${rag.routing.primary.deepseek.reasoning-effort:}") String reasoningEffort) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY 未配置——主模型（deepseek 形态）不可用");
        }
        // 思考治理（官方文档）：thinking 经 extra_body 透传（OpenAI SDK 无此标准字段）；
        // reasoning_effort 为标准参数走原生字段，仅显式配置时透传（缺省空=不传）
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
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
            .extraBody(Map.of("thinking", Map.of("type", thinkingType)));
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }
        return buildObservedModel(observationRegistryProvider, observationConventionProvider,
            optionsBuilder.build());
    }

    /**
     * 主模型（GLM 形态，缺省）——provider=glm（或缺省）时在场。
     * 密钥缺失快失败：与备用/Judge 同策。thinking 不传——官方唯一合法值 enabled
     * 即服务端默认，传 disabled 直接报错（官方文档实证）；effort 经原生字段透传。
     */
    @Bean("glmChatModel")
    @ConditionalOnProperty(name = "rag.routing.primary.provider", havingValue = "glm", matchIfMissing = true)
    public ChatModel glmChatModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            @Value("${rag.routing.primary.glm.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
            @Value("${rag.routing.primary.glm.api-key:}") String apiKey,
            @Value("${rag.routing.primary.glm.model:glm-5.3-flash}") String model,
            @Value("${rag.routing.primary.glm.temperature:1.0}") Double temperature,
            @Value("${rag.routing.primary.glm.max-tokens:4096}") Integer maxTokens,
            @Value("${rag.routing.primary.glm.reasoning-effort:}") String reasoningEffort) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ZHIPU_API_KEY 未配置——主模型（glm 形态）不可用");
        }
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .model(model)
            // 官方推荐采样参数（temperature 1.0）；思考模式下采样参数生效性未证，
            // 不构成退化基线——DeepSeek 思考形态下 temperature 同样静默不生效
            .temperature(temperature)
            .maxTokens(maxTokens)
            // D1：智谱 OpenAI 兼容端点支持 stream_options.include_usage（官方文档）；
            // 思维链 token 计入 completion_tokens（与 DeepSeek 思考形态同计量口径）
            .streamOptions(OpenAiChatOptions.StreamOptions.builder()
                .includeUsage(true)
                .build());
        if (reasoningEffort != null && !reasoningEffort.isBlank()) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }
        return buildObservedModel(observationRegistryProvider, observationConventionProvider,
            optionsBuilder.build());
    }

    /**
     * 主模型桥：当前 provider 选中的载体透传（互斥条件保证至多一个非空）。
     * 双空 = provider 配了非法值（如拼写错误），启动即失败并给出合法值域。
     */
    @Bean
    public ChatModel primaryChatModel(
            @Nullable @Qualifier("deepSeekChatModel") ChatModel deepSeek,
            @Nullable @Qualifier("glmChatModel") ChatModel glm) {
        if (deepSeek != null) {
            return deepSeek;
        }
        if (glm != null) {
            return glm;
        }
        throw new IllegalStateException(
            "rag.routing.primary.provider 取值非法——仅支持 deepseek | glm");
    }

    /**
     * 路由模型：主 + 备熔断切换。主模型经 {@code primaryChatModel} 桥注入（provider
     * 开关对路由层透明）。
     *
     * <p><b>@Primary 必要性（2026-08-05 启动失败实证）</b>：引入多 ChatModel Bean 后，
     * Spring AI {@code ChatClientAutoConfiguration#chatClientBuilder} 按类型裸注入
     * 单一 ChatModel（源码核验），多 Bean 歧义致启动失败——路由模型即应用级模型，
     * 标记 @Primary 统一消解（显式 @Qualifier 注入点不受影响）。
     */
    @Bean
    @Primary
    public ChatModel smartRoutingChatModel(
            @Qualifier("primaryChatModel") ChatModel primary,
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
     * 手工模型构建公共路径：显式挂 ObservationRegistry（坑位⑯——builder 单参形态
     * 内部 NOOP，自动装配注入不继承）+ 内容捕获 convention 条件接线（簇①：
     * 仅开关开启时注册，经 ObjectProvider 条件消费）。
     */
    private ChatModel buildObservedModel(
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObjectProvider<ChatModelObservationConvention> observationConventionProvider,
            OpenAiChatOptions options) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .options(options)
            .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
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
            // 模型由 starter 注入，手工 builder 不继承——E2E 观测实证）
            .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
            .build();
        observationConventionProvider.ifAvailable(chatModel::setObservationConvention);
        return chatModel;
    }

    /**
     * 内容捕获 convention（Phase 4 簇①）——仅内容捕获开关开启时注册（与
     * {@code spring.ai.chat.observations.log-prompt} 同源，同一 env 开关
     * RAG_OBSERVABILITY_LOG_CONTENT 驱动）。把 prompt/completion 作为高基数 KeyValue
     * 写入 ChatModel observation，经 tracing 桥落 span attribute（gen_ai.prompt/
     * gen_ai.completion，Langfuse OTLP 映射契约），补 Spring AI 2.0 内容只进日志不进
     * span 的缺口。主/备手工模型经 ObjectProvider 条件消费（见各 Bean）。
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.chat.observations.log-prompt", havingValue = "true")
    public ChatModelObservationConvention contentCapturingChatModelObservationConvention() {
        return new ContentCapturingChatModelObservationConvention();
    }
}
