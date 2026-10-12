package com.enterprise.kb.commons.guardrail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表重载信号载荷测试（v2.112 自跳板）——载荷带本实例标识、外部/畸形载荷恒不判自
 * （安全默认 = 重载而非忽略）。
 */
class GuardrailReloadSignalTest {

    @Test
    void payloadCarriesOwnInstanceId() {
        String payload = GuardrailReloadSignal.payload("crud-update");

        assertThat(payload).startsWith("crud-update|");
        assertThat(payload).endsWith(GuardrailReloadSignal.instanceId());
        assertThat(GuardrailReloadSignal.isSelf(payload)).isTrue();
    }

    @Test
    void instanceIdIsStableWithinProcess() {
        assertThat(GuardrailReloadSignal.instanceId())
            .isEqualTo(GuardrailReloadSignal.instanceId())
            .isNotBlank();
    }

    @Test
    void foreignOrMalformedPayloadsAreNeverSelf() {
        // 外部通道（publish_reload_signal.py / redis-cli）与旧格式载荷一律触发重载
        assertThat(GuardrailReloadSignal.isSelf("reload")).isFalse();
        assertThat(GuardrailReloadSignal.isSelf("migration")).isFalse();
        assertThat(GuardrailReloadSignal.isSelf("crud-update|another-instance-id")).isFalse();
        assertThat(GuardrailReloadSignal.isSelf("reload|")).isFalse();
        assertThat(GuardrailReloadSignal.isSelf(null)).isFalse();
        assertThat(GuardrailReloadSignal.isSelf("")).isFalse();
    }

    @Test
    void reasonIsNotParsedForBranching() {
        // reason 仅供日志定位：任意 reason 均不影响身份判定
        assertThat(GuardrailReloadSignal.isSelf(GuardrailReloadSignal.payload(""))).isTrue();
        assertThat(GuardrailReloadSignal.isSelf(
            GuardrailReloadSignal.payload("mtime-轮询-回落"))).isTrue();
    }
}
