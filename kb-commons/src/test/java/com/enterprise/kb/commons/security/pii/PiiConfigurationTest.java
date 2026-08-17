package com.enterprise.kb.commons.security.pii;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 识别器装配测试（安全簇③ C2）：配置开关 → 注册表收集语义
 * （直接调用 @Bean 工厂方法，无需 Spring 上下文）。
 */
class PiiConfigurationTest {

    @Test
    void defaultPropertiesEnableAllSevenDeterministicTypes() {
        PiiRecognizerRegistry registry =
            new PiiConfiguration().piiRecognizerRegistry(new PiiProperties());

        assertThat(registry.enabledTypes()).containsExactly(
            PiiType.PHONE, PiiType.ID_CARD, PiiType.EMAIL, PiiType.BANK_CARD,
            PiiType.LANDLINE, PiiType.LICENSE_PLATE, PiiType.IPV4);
    }

    @Test
    void disabledTypeExcludedFromRegistry() {
        PiiProperties properties = new PiiProperties();
        properties.getBankCard().setEnabled(false);
        properties.getIpv4().setEnabled(false);

        PiiRecognizerRegistry registry = new PiiConfiguration().piiRecognizerRegistry(properties);

        assertThat(registry.enabledTypes())
            .doesNotContain(PiiType.BANK_CARD, PiiType.IPV4)
            .hasSize(5);
        // 关停类型原样直通（误报治理运维绕开面）
        assertThat(registry.mask("服务器 192.168.1.10")).isEqualTo("服务器 192.168.1.10");
    }

    @Test
    void reservedTypeSwitchHasNoRecognizerEffect() {
        // C3 登记项（NAME/ADDRESS）开关置 true 仅启动 warn——注册表形态不变
        PiiProperties properties = new PiiProperties();
        properties.getName().setEnabled(true);
        properties.getAddress().setEnabled(true);

        PiiRecognizerRegistry registry = new PiiConfiguration().piiRecognizerRegistry(properties);

        assertThat(registry.enabledTypes()).hasSize(7);
    }
}
