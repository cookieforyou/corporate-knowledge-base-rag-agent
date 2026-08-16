package com.enterprise.kb.ai.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统提示金丝雀测试（安全簇① T5）——运行时随机 token、动态断言不硬编码（纪律检查点）
 */
class PromptCanaryTest {

    @Test
    void enabledCanaryGeneratesTokenAndEmbedsIntoSystemPrompt() {
        PromptCanary canary = new PromptCanary(true);

        assertThat(canary.enabled()).isTrue();
        assertThat(canary.token()).startsWith("kb-canary-").hasSizeGreaterThan(20);

        String embedded = canary.embed("你是企业知识库助手。");
        assertThat(embedded).startsWith("你是企业知识库助手。").contains(canary.token());

        assertThat(canary.leakedIn("前缀 " + canary.token() + " 后缀")).isTrue();
        assertThat(canary.leakedIn("无关输出文本")).isFalse();
        assertThat(canary.leakedIn(null)).isFalse();
    }

    @Test
    void disabledCanaryIsIdentityAndNeverLeaks() {
        PromptCanary canary = new PromptCanary(false);

        assertThat(canary.enabled()).isFalse();
        assertThat(canary.token()).isEmpty();
        assertThat(canary.embed("原样系统提示")).isSameAs("原样系统提示");
        assertThat(canary.leakedIn("任意文本 kb-canary-xxxx")).isFalse();
    }

    @Test
    void instancesGenerateDistinctTokens() {
        assertThat(new PromptCanary(true).token()).isNotEqualTo(new PromptCanary(true).token());
    }
}
