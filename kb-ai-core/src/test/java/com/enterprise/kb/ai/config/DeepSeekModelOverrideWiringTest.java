package com.enterprise.kb.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主模型手工装配 vs deepseek starter 共存回归（v2.19 簇③ D1，双向钉死）
 *
 * <p><b>正向</b>：{@code spring.ai.model.chat=none}（生产 yml 形态）时 starter
 * 整体让位（其类级 @ConditionalOnProperty havingValue=deepseek），
 * {@code deepSeekChatModel} 为手工装配的 OpenAI 兼容形态。
 *
 * <p><b>反向</b>：该键回写 {@code deepseek} 时，starter 复活并注册同名 Bean——
 * Boot 4.1 实测抛 BeanDefinitionOverrideException（同名让位不成立，
 * 2026-08-11 本测试首版实证）。此用例钉死「chat 键不得回写 deepseek」约束，
 * 防止后人恢复 starter 形态致启动失败或流式配额漏算复活。
 */
class DeepSeekModelOverrideWiringTest {

    private ApplicationContextRunner runner(String chatProvider) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class))
            .withUserConfiguration(SmartRoutingConfig.class)
            .withPropertyValues(
                "spring.ai.model.chat=" + chatProvider,
                "spring.ai.deepseek.api-key=sk-test",
                "rag.routing.fallback.api-key=sk-fallback");
    }

    @Test
    void chatProviderNone_starterStandsDown_manualPrimaryWins() {
        runner("none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DeepSeekChatModel.class)).isEmpty();
            ChatModel primary = context.getBean("deepSeekChatModel", ChatModel.class);
            assertThat(primary).isInstanceOf(OpenAiChatModel.class);
            // 路由与备用模型正常组装
            assertThat(context).hasBean("smartRoutingChatModel");
            assertThat(context).hasBean("fallbackChatModel");
        });
    }

    @Test
    void chatProviderDeepseek_sameNameConflictFailsStartup() {
        runner("deepseek").run(context -> {
            assertThat(context).hasFailed();
            // BeanDefinitionOverrideException 即叶子异常（无 cause 链，实证）
            Throwable failure = context.getStartupFailure();
            assertThat(failure.getMessage()).contains("deepSeekChatModel");
        });
    }
}
