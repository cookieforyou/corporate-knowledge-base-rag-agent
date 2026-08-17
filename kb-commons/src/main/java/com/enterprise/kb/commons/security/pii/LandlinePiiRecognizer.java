package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 座机号识别器（安全簇③ C1 新增）：0 开头区号（3-4 位，010/02x 三位、
 * 03xx-09xx 四位）+ 7-8 位本地号码，区号与号码间允许空格/连字符，
 * 边界断言防长数字串内部误匹配。
 *
 * <p><b>启发式定位</b>（置信度 0.8）：10-12 位 0 开头数字串的形态判定，
 * 与手机号（1 开头）/身份证（18 位）/银行卡（Luhn 13-19 位）顺序正交，
 * 注册表序 PHONE → ID_CARD → EMAIL → BANK_CARD → LANDLINE 消解交叠。
 */
public final class LandlinePiiRecognizer extends RegexPiiRecognizer {

    private static final Pattern PATTERN =
        Pattern.compile("(?<!\\d)0\\d{2,3}[ \\-]?\\d{7,8}(?!\\d)");

    private static final String MASK = "0***-********";

    public LandlinePiiRecognizer() {
        super(PiiType.LANDLINE, 0.8, PATTERN, MASK);
    }
}
