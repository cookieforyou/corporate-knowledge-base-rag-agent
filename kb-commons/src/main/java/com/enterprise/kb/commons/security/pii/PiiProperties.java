package com.enterprise.kb.commons.security.pii;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PII 识别器开关配置族（安全簇③ C2，专项方案 §4.3 定案键形
 * {@code rag.guardrail.pii.{type}.enabled}）——每类型独立开关，误报治理的
 * 运维绕开面（某类型误报即关该类型，不牵动其余识别器）。
 *
 * <p>七类确定性识别器默认全开（行为 = 簇②前既有三类掩码 + C1 新增四类）；
 * {@code name}/{@code address} 为 C3 登记项（NER 依赖、误报管理成本高）默认关，
 * 开关键预留但识别器未实现——置 true 仅启动 warn 提示无效（装配校验见
 * {@code PiiConfiguration}），待干净集误报度量后再评估实现。
 *
 * <p>环境变量覆盖走 Spring 松散绑定（如 {@code RAG_GUARDRAIL_PII_BANK_CARD_ENABLED=false}
 * 关银行卡识别），无需逐键占位符。
 */
@ConfigurationProperties(prefix = "rag.guardrail.pii")
public class PiiProperties {

    private TypeSwitch phone = TypeSwitch.enabled();
    private TypeSwitch idCard = TypeSwitch.enabled();
    private TypeSwitch email = TypeSwitch.enabled();
    private TypeSwitch bankCard = TypeSwitch.enabled();
    private TypeSwitch landline = TypeSwitch.enabled();
    private TypeSwitch licensePlate = TypeSwitch.enabled();
    private TypeSwitch ipv4 = TypeSwitch.enabled();

    /** C3 登记项（NER 依赖未实现）：默认关，开关预留 */
    private TypeSwitch name = TypeSwitch.disabled();
    private TypeSwitch address = TypeSwitch.disabled();

    public TypeSwitch getPhone() {
        return phone;
    }

    public void setPhone(TypeSwitch phone) {
        this.phone = phone;
    }

    public TypeSwitch getIdCard() {
        return idCard;
    }

    public void setIdCard(TypeSwitch idCard) {
        this.idCard = idCard;
    }

    public TypeSwitch getEmail() {
        return email;
    }

    public void setEmail(TypeSwitch email) {
        this.email = email;
    }

    public TypeSwitch getBankCard() {
        return bankCard;
    }

    public void setBankCard(TypeSwitch bankCard) {
        this.bankCard = bankCard;
    }

    public TypeSwitch getLandline() {
        return landline;
    }

    public void setLandline(TypeSwitch landline) {
        this.landline = landline;
    }

    public TypeSwitch getLicensePlate() {
        return licensePlate;
    }

    public void setLicensePlate(TypeSwitch licensePlate) {
        this.licensePlate = licensePlate;
    }

    public TypeSwitch getIpv4() {
        return ipv4;
    }

    public void setIpv4(TypeSwitch ipv4) {
        this.ipv4 = ipv4;
    }

    public TypeSwitch getName() {
        return name;
    }

    public void setName(TypeSwitch name) {
        this.name = name;
    }

    public TypeSwitch getAddress() {
        return address;
    }

    public void setAddress(TypeSwitch address) {
        this.address = address;
    }

    /** 单类型开关载体 */
    public static final class TypeSwitch {

        private boolean enabled;

        static TypeSwitch enabled() {
            TypeSwitch s = new TypeSwitch();
            s.enabled = true;
            return s;
        }

        static TypeSwitch disabled() {
            return new TypeSwitch();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
