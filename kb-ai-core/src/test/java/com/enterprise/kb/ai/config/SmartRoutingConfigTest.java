package com.enterprise.kb.ai.config;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 模型装配回归（v2.19 簇③ D1）：主/备模型均为手工装配的 OpenAI 兼容形态，
 * 且都开启 stream_options.include_usage——流式 token 计账（TokenBudgetAdvisor/
 * 审计末块回写）的装配前提，防止后续改动静默回退致配额再次漏算。
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
    void primaryModelOpensStreamUsageAndKeepsBaselineParameters() {
        ChatModel model = config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "sk-test", "https://api.deepseek.com", "deepseek-v4-flash", 0.1, 4096);

        OpenAiChatOptions options = optionsOf(model);
        assertThat(options.getStreamOptions()).isNotNull();
        assertThat(options.getStreamOptions().includeUsage()).isTrue();
        assertThat(options.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(options.getMaxTokens()).isEqualTo(4096);
        assertThat(options.getTemperature()).isEqualTo(0.1);
        assertThat(options.getBaseUrl()).isEqualTo("https://api.deepseek.com");
    }

    @Test
    void primaryModelFailsFastOnMissingApiKey() {
        assertThatThrownBy(() -> config.deepSeekChatModel(registryProvider(), noConventionProvider(),
            "", "https://api.deepseek.com", "deepseek-v4-flash", 0.1, 4096))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DEEPSEEK_API_KEY");
    }

    @Test
    void fallbackModelOpensStreamUsageAndKeepsThinkingOff() {
        ChatModel model = config.fallbackChatModel(registryProvider(), noConventionProvider(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-test",
            "qwen3.7-plus", 0.1, false);

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
            "qwen3.7-plus", 0.1, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DASHSCOPE_API_KEY");
    }

    /** 簇① 回归：convention 在场时接线至手工模型（缺省态不设置——上方四用例已过） */
    @Test
    void contentConventionWiredWhenPresent() throws Exception {
        ChatModel model = config.deepSeekChatModel(registryProvider(), conventionProvider(),
            "sk-test", "https://api.deepseek.com", "deepseek-v4-flash", 0.1, 4096);

        assertThat(conventionOf(model)).isInstanceOf(ContentCapturingChatModelObservationConvention.class);

        ChatModel fallback = config.fallbackChatModel(registryProvider(), conventionProvider(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-test",
            "qwen3.7-plus", 0.1, false);
        assertThat(conventionOf(fallback)).isInstanceOf(ContentCapturingChatModelObservationConvention.class);
    }
}
