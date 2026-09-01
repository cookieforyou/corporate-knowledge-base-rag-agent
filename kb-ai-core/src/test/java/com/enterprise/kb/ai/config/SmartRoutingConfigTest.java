package com.enterprise.kb.ai.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型装配回归（v2.19 簇③ D1 / v2.77 模型层批B）：主模型双形态（GLM 缺省 /
 * DeepSeek 回落）与备用模型均为手工装配的 OpenAI 兼容形态，且都开启
 * stream_options.include_usage——流式 token 计账（TokenBudgetAdvisor/审计末块
 * 回写）的装配前提，防止后续改动静默回退致配额再次漏算。
 *
 * <p>v2.77 新增断言面：DeepSeek 思考治理（缺省 disabled + effort 条件透传）、
 * GLM 思考零配置（thinking 不可关——不传即唯一合法默认）、primaryChatModel
 * 桥三态（deepseek / glm / 双空快失败）。
 */
class SmartRoutingConfigTest {

    private final SmartRoutingConfig config = new SmartRoutingConfig();

    /** 单值 ObjectProvider 桩（ObservationRegistry 注入位） */
    private static ObjectProvider<ObservationRegistry> registryProvider() {
        return new ObjectProvider<>() {
            @Override
            public ObservationRegistry getObject() {
                return ObservationRegistry.NOOP;
            }
        };
    }

    /** 空 ObjectProvider 桩（内容捕获 convention 缺省态——不设置 convention） */
    private static ObjectProvider<ChatModelObservationConvention> noConventionProvider() {
        return new ObjectProvider<>() {
            @Override
            public ChatModelObservationConvention getObject() {
                return null;
            }

            @Override
            public ChatModelObservationConvention getIfAvailable() {
                return null;
            }
        };
    }

    /** 单值 ObjectProvider 桩（内容捕获 convention 在场态） */
    private static ObjectProvider<ChatModelObservationConvention> conventionProvider() {
        ContentCapturingChatModelObservationConvention convention =
            new ContentCapturingChatModelObservationConvention();
        return new ObjectProvider<>() {
            @Override
            public ChatModelObservationConvention getObject() {
                return convention;
            }

            @Override
            public ChatModelObservationConvention getIfAvailable() {
                return convention;
            }
        };
    }

    /** OpenAiChatModel 无 convention getter，反射读私有字段钉接线（簇① 回归锚点） */
    private static Object conventionOf(ChatModel model) throws Exception {
        Field field = OpenAiChatModel.class.getDeclaredField("observationConvention");
        field.setAccessible(true);
        return field.get(model);
    }

    private static OpenAiChatOptions optionsOf(ChatModel model) {
        return (OpenAiChatOptions) ((OpenAiChatModel) model).getOptions();
    }

    @Test
    void primaryDeepSeekThinkingDisabledByDefault() {
        ChatModel model = config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "https://api.deepseek.com", "sk-test", "deepseek-v4-flash", 0.1, 4096, "disabled", "");

        OpenAiChatOptions options = optionsOf(model);
        assertThat(options.getStreamOptions()).isNotNull();
        assertThat(options.getStreamOptions().includeUsage()).isTrue();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(options.getMaxTokens()).isEqualTo(4096);
        assertThat(options.getTemperature()).isEqualTo(0.1);
        assertThat(options.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        // 思考治理（官方文档实证：默认开 + effort high 是隐藏成本负担）——回落形态
        // 缺省显式关思考；thinking 经 extra_body 透传（OpenAI SDK 无此标准字段）
        assertThat(options.getExtraBody())
            .containsEntry("thinking", Map.of("type", "disabled"));
        // effort 空不透传（服务端默认仅思考开时才有意义）
        assertThat(options.getReasoningEffort()).isNull();
    }

    @Test
    void primaryDeepSeekThinkingEnabledWithEffort() {
        ChatModel model = config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "https://api.deepseek.com", "sk-test", "deepseek-v4-flash", 0.1, 4096, "enabled", "low");

        OpenAiChatOptions options = optionsOf(model);
        assertThat(options.getExtraBody())
            .containsEntry("thinking", Map.of("type", "enabled"));
        assertThat(options.getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void primaryGlmThinkingUntouchedAndStreamUsageOn() {
        ChatModel model = config.glmChatModel(registryProvider(), noConventionProvider(),
            "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.3-flash", 1.0, 4096, "");

        OpenAiChatOptions options = optionsOf(model);
        assertThat(options.getStreamOptions()).isNotNull();
        assertThat(options.getStreamOptions().includeUsage()).isTrue();
        assertThat(options.getModel()).isEqualTo("glm-5.3-flash");
        assertThat(options.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(options.getTemperature()).isEqualTo(1.0);
        // thinking 零配置：官方唯一合法值 enabled 即服务端默认（传 disabled 报错）；
        // effort 空不透传（缺省 max 由服务端决定，生产档位经冒烟定档）
        assertThat(options.getExtraBody()).isNullOrEmpty();
        assertThat(options.getReasoningEffort()).isNull();
    }

    @Test
    void primaryGlmEffortPassedThrough() {
        ChatModel model = config.glmChatModel(registryProvider(), noConventionProvider(),
            "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.3-flash", 1.0, 4096, "low");

        assertThat(optionsOf(model).getReasoningEffort()).isEqualTo("low");
    }

    @Test
    void primaryChatModelBridgeSelectsNonNullable() {
        ChatModel ds = config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "https://api.deepseek.com", "sk-test", "deepseek-v4-flash", 0.1, 4096, "disabled", "");
        ChatModel glm = config.glmChatModel(registryProvider(), noConventionProvider(),
            "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.3-flash", 1.0, 4096, "");

        assertThat(config.primaryChatModel(ds, null)).isSameAs(ds);
        assertThat(config.primaryChatModel(null, glm)).isSameAs(glm);
        // provider 非法值（拼写错误）→ 双空快失败并给出合法值域
        assertThatThrownBy(() -> config.primaryChatModel(null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deepseek | glm");
    }

    @Test
    void primaryModelsFailFastOnMissingApiKey() {
        assertThatThrownBy(() -> config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "https://api.deepseek.com", "", "deepseek-v4-flash", 0.1, 4096, "disabled", ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DEEPSEEK_API_KEY");
        assertThatThrownBy(() -> config.glmChatModel(registryProvider(), noConventionProvider(),
            "https://open.bigmodel.cn/api/paas/v4", "", "glm-5.3-flash", 1.0, 4096, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ZHIPU_API_KEY");
    }

    @Test
    void fallbackModelOpensStreamUsageAndKeepsThinkingOff() {
        ChatModel model = config.fallbackChatModel(registryProvider(), noConventionProvider(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-test",
            "qwen3.8-flash", 0.1, false);

        OpenAiChatOptions options = optionsOf(model);
        assertThat(options.getStreamOptions()).isNotNull();
        assertThat(options.getStreamOptions().includeUsage()).isTrue();
        // 故障接管场景思考模式必须关（单调用 20-60s 不可接受，见 SmartRoutingConfig 注）
        assertThat(options.getExtraBody()).containsEntry("enable_thinking", false);
    }

    @Test
    void fallbackModelFailsFastOnMissingApiKey() {
        assertThatThrownBy(() -> config.fallbackChatModel(registryProvider(), noConventionProvider(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "",
            "qwen3.8-flash", 0.1, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    /** 簇① 回归：convention 在场时接线至手工模型（缺省态不设置——上方用例已过） */
    @Test
    void contentConventionWiredWhenPresent() throws Exception {
        ChatModel glm = config.glmChatModel(registryProvider(), conventionProvider(),
            "https://open.bigmodel.cn/api/paas/v4", "sk-test", "glm-5.3-flash", 1.0, 4096, "");
        assertThat(conventionOf(glm)).isInstanceOf(ContentCapturingChatModelObservationConvention.class);

        ChatModel fallback = config.fallbackChatModel(registryProvider(), conventionProvider(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-test",
            "qwen3.8-flash", 0.1, false);
        assertThat(conventionOf(fallback)).isInstanceOf(ContentCapturingChatModelObservationConvention.class);
    }
}
