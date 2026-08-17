package com.enterprise.kb.commons.security.pii;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 识别器注册表测试（安全簇③ C1/C2）——既有三类零漂移回归 + C1 四类新识别器
 * （银行卡 Luhn / 座机 / 车牌 / IPv4）+ 注册表语义（顺序/开关/报告/检测/幂等）。
 *
 * <p>银行卡测试号以程序化构造为主（前缀 + 计算校验位），另用公开发布的标准
 * 测试卡号（非真实卡）；其余数字形态均为合成测试数据。
 */
class PiiRecognizerRegistryTest {

    private final PiiRecognizerRegistry registry = PiiRecognizerRegistry.defaults();

    /** 前缀 + 枚举校验位构造 Luhn 有效卡号（程序化，测试源码不落真实卡面） */
    private static String luhnComplete(String prefix) {
        for (int d = 0; d <= 9; d++) {
            String candidate = prefix + d;
            if (BankCardPiiRecognizer.luhnValid(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("校验位枚举不可达");
    }

    // ── 既有三类行为零漂移（TextSanitizer 原实现迁入）──

    @Test
    void masksPhoneIdCardAndEmail() {
        String sanitized = registry.mask(
            "联系人 13812345678，身份证 110101199003077758，邮箱 zhang.san@corp.com");

        assertThat(sanitized)
            .contains("1***-****-****")
            .contains("******************")
            .contains("***@***.***")
            .doesNotContain("13812345678")
            .doesNotContain("110101199003077758")
            .doesNotContain("zhang.san@corp.com");
    }

    @Test
    void masksSpaceAndHyphenSeparatedPhone() {
        // G2：空格/连字符拆词形态同样落网
        assertThat(registry.mask("电话 138 1234 5678 备用 139-1111-2222"))
            .contains("1***-****-****")
            .doesNotContain("138 1234 5678")
            .doesNotContain("139-1111-2222");
    }

    @Test
    void masksSeparatedIdCard() {
        assertThat(registry.mask("证件号 110101-19900307-7758"))
            .contains("******************")
            .doesNotContain("110101-19900307-7758");
    }

    @Test
    void boundaryGuardsPreventFalsePositivesInsideLongerNumbers() {
        // 19 位订单号（Luhn 无效）内部不构成任何数字类 PII——边界断言防误伤
        String longNumber = "订单号 2026138123456789012 请核对";

        assertThat(registry.mask(longNumber)).isEqualTo(longNumber);
    }

    @Test
    void maskingIsIdempotent() {
        String once = registry.mask("手机 13812345678");

        assertThat(registry.mask(once)).isEqualTo(once);
    }

    // ── C1 银行卡（Luhn 校验 + 分隔符容忍 + 边界断言）──

    @Test
    void masksLuhnValidBankCardWithSeparators() {
        String valid16 = luhnComplete("622202020011223");   // 15 位前缀 + 校验位
        String valid19 = luhnComplete("622202020011223398"); // 18 位前缀 + 校验位（19 位形态）

        assertThat(registry.mask("卡号 " + valid16 + " 与 " + valid19))
            .contains("****-****-****-****")
            .doesNotContain(valid16)
            .doesNotContain(valid19);
        assertThat(registry.mask("卡号 4111 1111 1111 1111"))
            .as("公开发布的标准测试卡号（分隔符形态）应落网")
            .contains("****-****-****-****");
    }

    @Test
    void luhnInvalidLongDigitsLeftUntouched() {
        String valid = luhnComplete("622202020011223");
        char last = valid.charAt(valid.length() - 1);
        String invalid = valid.substring(0, valid.length() - 1) + (last == '9' ? '0' : (char) (last + 1));

        assertThat(registry.mask("流水号 " + invalid)).isEqualTo("流水号 " + invalid);
    }

    @Test
    void twentyPlusDigitRunsNotMatched() {
        // 20 位数字串超出卡号长度域——边界断言双保险
        String twenty = "9".repeat(20);

        assertThat(registry.mask("账号 " + twenty)).isEqualTo("账号 " + twenty);
    }

    // ── C1 座机 ──

    @Test
    void masksLandlineForms() {
        assertThat(registry.mask("总机 010-12345678，分部 0755 1234567"))
            .contains("0***-********")
            .doesNotContain("010-12345678")
            .doesNotContain("0755 1234567");
    }

    @Test
    void mobileNotMisroutedToLandline() {
        // 手机号（1 开头）不落入座机（0 开头）——类型正交
        PiiMaskResult result = registry.maskWithReport("手机 13911112222");

        assertThat(result.hitTypes()).containsExactly(PiiType.PHONE);
    }

    // ── C1 车牌 ──

    @Test
    void masksLicensePlateKeepingPrefix() {
        assertThat(registry.mask("车辆 京A12345 已登记"))
            .contains("京A*****")
            .doesNotContain("京A12345");
        // 新能源 6 位序号形态
        assertThat(registry.mask("车辆 粤BD12345 已登记"))
            .contains("粤B*****")
            .doesNotContain("粤BD12345");
        // 特殊尾字符形态（挂/学）
        assertThat(registry.mask("车辆 京A1234挂")).contains("京A*****");
    }

    @Test
    void plateInsideAlphanumericRunNotMatched() {
        // 前邻字母——词内边界断言防误匹配
        String embedded = "编号X京A12345存档";

        assertThat(registry.mask(embedded)).isEqualTo(embedded);
    }

    // ── C1 IPv4 ──

    @Test
    void masksIpv4WithOctetValidation() {
        assertThat(registry.mask("服务器 192.168.1.10 与 10.0.0.1 在线"))
            .contains("***.***.***.***")
            .doesNotContain("192.168.1.10")
            .doesNotContain("10.0.0.1");
    }

    @Test
    void invalidOctetAndLongerDottedFormsNotMatched() {
        assertThat(registry.mask("异常 256.1.1.1")).isEqualTo("异常 256.1.1.1");
        // 五段点分串内部不误匹配
        assertThat(registry.mask("段列 1.2.3.4.5")).isEqualTo("段列 1.2.3.4.5");
        // 三段版本号不误匹配
        assertThat(registry.mask("版本 1.28.3 发布")).isEqualTo("版本 1.28.3 发布");
    }

    // ── 注册表语义：顺序 / 报告 / 检测 / 开关 / null 安全 ──

    @Test
    void maskWithReportCollectsHitTypes() {
        PiiMaskResult result = registry.maskWithReport(
            "手机 13911112222，服务器 192.168.1.10");

        assertThat(result.text())
            .contains("1***-****-****")
            .contains("***.***.***.***");
        assertThat(result.hitTypes()).containsExactly(PiiType.PHONE, PiiType.IPV4);
    }

    @Test
    void noHitReturnsEmptyReportAndSameText() {
        PiiMaskResult result = registry.maskWithReport("干净文本");

        assertThat(result.text()).isEqualTo("干净文本");
        assertThat(result.hitTypes()).isEmpty();
    }

    @Test
    void eighteenDigitRunMasksAsIdCardBeforeBankCard() {
        // 交叠口径：18 位纯数字串（即便 Luhn 有效）先落身份证——既有顺序语义保留
        String eighteen = luhnComplete("11010119900307775");
        PiiMaskResult result = registry.maskWithReport("证件 " + eighteen);

        assertThat(result.hitTypes()).containsExactly(PiiType.ID_CARD);
        assertThat(result.text()).contains("******************");
    }

    @Test
    void disabledRecognizerLeavesItsTypeUntouched() {
        // 开关语义的注册表侧投影：仅启用手机识别器 → 邮箱原样直通
        PiiRecognizerRegistry phoneOnly =
            new PiiRecognizerRegistry(List.of(new PhonePiiRecognizer()));

        assertThat(phoneOnly.mask("邮箱 a@b.com 手机 13911112222"))
            .contains("a@b.com")
            .contains("1***-****-****");
        assertThat(phoneOnly.enabledTypes()).containsExactly(PiiType.PHONE);
    }

    @Test
    void detectReturnsSpansWithoutValues() {
        List<PiiHit> hits = registry.detect("联系 13911112222 吧");

        assertThat(hits).hasSize(1);
        PiiHit hit = hits.get(0);
        assertThat(hit.type()).isEqualTo(PiiType.PHONE);
        assertThat(hit.start()).isEqualTo(3);
        assertThat(hit.end()).isEqualTo(14);
        assertThat(hit.confidence()).isEqualTo(1.0);
    }

    @Test
    void defaultsRegistryEnablesSevenDeterministicTypesInOrder() {
        assertThat(registry.enabledTypes()).containsExactly(
            PiiType.PHONE, PiiType.ID_CARD, PiiType.EMAIL, PiiType.BANK_CARD,
            PiiType.LANDLINE, PiiType.LICENSE_PLATE, PiiType.IPV4);
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertThat(registry.mask(null)).isNull();
        assertThat(registry.mask("")).isEmpty();
        assertThat(registry.detect(null)).isEmpty();
        assertThat(registry.detect("")).isEmpty();
        assertThat(registry.maskWithReport(null).text()).isNull();
    }

    @Test
    void maskingMixedAllTypesIsIdempotent() {
        String mixed = "手机 13911112222，证件 110101199003077758，邮箱 a@b.com，"
            + "卡号 4111 1111 1111 1111，总机 010-12345678，车辆 京A12345，服务器 192.168.1.10";

        String once = registry.mask(mixed);

        assertThat(registry.mask(once)).isEqualTo(once);
    }
}
