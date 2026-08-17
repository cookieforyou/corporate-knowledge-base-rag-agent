package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 身份证号识别器（既有三类迁入识别器形态，安全簇③ C2——模式与掩码逐字沿用
 * TextSanitizer 原实现，零行为漂移）：18 位（末位可 X），数字间允许空格/连字符。
 */
public final class IdCardPiiRecognizer extends RegexPiiRecognizer {

    private static final Pattern PATTERN =
        Pattern.compile("(?<!\\d)\\d(?:[ \\-]?\\d){16}[ \\-]?[\\dXx](?![\\dXx])");

    private static final String MASK = "******************";

    public IdCardPiiRecognizer() {
        super(PiiType.ID_CARD, 1.0, PATTERN, MASK);
    }
}
