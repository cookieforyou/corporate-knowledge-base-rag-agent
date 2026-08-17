package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 手机号识别器（既有三类迁入识别器形态，安全簇③ C2——模式与掩码逐字沿用
 * TextSanitizer 原实现，零行为漂移）：1[3-9] 开头 11 位，数字间允许空格/连字符
 * （G2 拆词绕过防护），边界断言防长数字串内部误匹配（v2.4 纪律）。
 */
public final class PhonePiiRecognizer extends RegexPiiRecognizer {

    private static final Pattern PATTERN =
        Pattern.compile("(?<!\\d)1[3-9](?:[ \\-]?\\d){9}(?!\\d)");

    private static final String MASK = "1***-****-****";

    public PhonePiiRecognizer() {
        super(PiiType.PHONE, 1.0, PATTERN, MASK);
    }
}
