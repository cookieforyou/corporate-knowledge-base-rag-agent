package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 邮箱识别器（既有三类迁入识别器形态，安全簇③ C2——模式与掩码逐字沿用
 * TextSanitizer 原实现，零行为漂移）。
 */
public final class EmailPiiRecognizer extends RegexPiiRecognizer {

    private static final Pattern PATTERN =
        Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    private static final String MASK = "***@***.***";

    public EmailPiiRecognizer() {
        super(PiiType.EMAIL, 1.0, PATTERN, MASK);
    }
}
