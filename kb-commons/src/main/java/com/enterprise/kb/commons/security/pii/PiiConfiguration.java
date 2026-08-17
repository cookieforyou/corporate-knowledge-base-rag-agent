package com.enterprise.kb.commons.security.pii;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * PII 识别器注册表装配（安全簇③ C2）：按 {@link PiiProperties} 开关依注册顺序
 * 收集启用识别器装配注册表 Bean——kb-api/kb-eval 两上下文均扫描 com.enterprise.kb，
 * 对话链/ETL/审计全部消费方注入同一 Bean（单一实现源纪律承载，见
 * {@link PiiRecognizerRegistry} 类注）。
 *
 * <p>C3 登记项（NAME/ADDRESS）开关键预留但识别器未实现——置 true 时启动 warn
 * 提示无效（fail-safe 静默降级，不阻断启动），待干净集误报度量后再评估实现。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PiiProperties.class)
public class PiiConfiguration {

    @Bean
    public PiiRecognizerRegistry piiRecognizerRegistry(PiiProperties properties) {
        List<PiiRecognizer> enabled = new ArrayList<>();
        // 注册顺序即掩码优先级（交叠消解口径，见 PiiRecognizerRegistry 类注）
        if (properties.getPhone().isEnabled()) {
            enabled.add(new PhonePiiRecognizer());
        }
        if (properties.getIdCard().isEnabled()) {
            enabled.add(new IdCardPiiRecognizer());
        }
        if (properties.getEmail().isEnabled()) {
            enabled.add(new EmailPiiRecognizer());
        }
        if (properties.getBankCard().isEnabled()) {
            enabled.add(new BankCardPiiRecognizer());
        }
        if (properties.getLandline().isEnabled()) {
            enabled.add(new LandlinePiiRecognizer());
        }
        if (properties.getLicensePlate().isEnabled()) {
            enabled.add(new LicensePlatePiiRecognizer());
        }
        if (properties.getIpv4().isEnabled()) {
            enabled.add(new Ipv4PiiRecognizer());
        }
        if (properties.getName().isEnabled() || properties.getAddress().isEnabled()) {
            log.warn("PII 识别器 NAME/ADDRESS 为 C3 登记项（NER 依赖，识别器未实现），"
                + "开关置 true 无效——待干净集误报度量后再评估（专项方案 §4.3）");
        }
        log.info("PII 识别器注册表装配: 启用 {} 类（{}）", enabled.size(),
            enabled.stream().map(r -> r.type().name()).toList());
        return new PiiRecognizerRegistry(enabled);
    }
}
