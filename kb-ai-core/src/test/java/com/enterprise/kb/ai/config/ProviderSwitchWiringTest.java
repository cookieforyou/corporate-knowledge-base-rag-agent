package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主模型双形态开关装配回归（v2.77 模型层批B）——接替退役的
 * DeepSeekModelOverrideWiringTest（deepseek starter 让位机制，v2.19-v2.76）。
 *
 * <p>三态钉死：缺省=glm / 显式 deepseek / 非法值启动即失败。切换只动
 * {@code rag.routing.primary.provider} 一个键，载体 Bean 与 primaryChatModel
 * 桥的指向关系防后续改动静默漂移。
 */
class ProviderSwitchWiringTest {

    private ApplicationContextRunner runner(String... props) {
        return new ApplicationContextRunner()
            .withUserConfiguration(SmartRoutingConfig.class)
            // 路由 SLA 计数依赖：最小上下文以独立 registry 供装配
            .withBean(AiBusinessMetrics.class, () -> new AiBusinessMetrics(new SimpleMeterRegistry()))
            .withPropertyValues(props);
    }

    @Test
    void defaultProvider_glmWins() {
        runner("rag.routing.primary.glm.api-key=sk-glm",
               "rag.routing.fallback.api-key=sk-fallback").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("glmChatModel");
            assertThat(context).doesNotHaveBean("deepSeekChatModel");
            // 桥透传当前形态，路由层（smartRoutingChatModel）对开关零感知
            ChatModel primary = context.getBean("primaryChatModel", ChatModel.class);
            assertThat(primary).isSameAs(context.getBean("glmChatModel", ChatModel.class));
            assertThat(context).hasBean("smartRoutingChatModel");
            assertThat(context).hasBean("fallbackChatModel");
        });
    }

    @Test
    void deepseekProvider_deepseekWins() {
        runner("rag.routing.primary.provider=deepseek",
               "rag.routing.primary.deepseek.api-key=sk-ds",
               "rag.routing.fallback.api-key=sk-fallback").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("deepSeekChatModel");
            assertThat(context).doesNotHaveBean("glmChatModel");
            ChatModel primary = context.getBean("primaryChatModel", ChatModel.class);
            assertThat(primary).isSameAs(context.getBean("deepSeekChatModel", ChatModel.class));
        });
    }

    @Test
    void illegalProvider_failsFastWithLegalValues() {
        runner("rag.routing.primary.provider=gpt",
               "rag.routing.fallback.api-key=sk-fallback").run(context -> {
            assertThat(context).hasFailed();
            // 双空桥快失败并给出合法值域（拼写错误在启动期暴露，不带病运行；
            // 失败态上下文不可做 Bean 存在性断言，仅检 startupFailure 文本）
            assertThat(context.getStartupFailure())
                .hasMessageContaining("deepseek | glm");
        });
    }
}
