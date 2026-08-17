package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 车牌号识别器（安全簇③ C1 新增）：省份汉字（31 省级行政区简称）+ 发牌机关
 * 代号（A-Z 去 I/O）+ 序号——常规 5 位与新能源 6 位形态（序号末位含
 * 挂/学/警/港/澳特殊字符形态），前后字母数字边界断言防词内误匹配。
 *
 * <p><b>掩码保留前两字符</b>（省份简称 + 发牌机关代号，低敏定位信息），
 * 序号段以星号替换；掩码形态不含序号字符类，幂等不二次匹配。
 */
public final class LicensePlatePiiRecognizer extends RegexPiiRecognizer {

    private static final Pattern PATTERN = Pattern.compile(
        "(?<![A-Za-z0-9])"
            + "[京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新]"
            + "[A-HJ-NP-Z]"
            + "[A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]"
            + "(?![A-Za-z0-9])");

    /** 占位掩码字面（实际掩码经 {@link #mask} 动态保留前两字符，字面仅用于语义标注） */
    private static final String MASK = "*****";

    public LicensePlatePiiRecognizer() {
        super(PiiType.LICENSE_PLATE, 0.9, PATTERN, MASK);
    }

    /** 掩码动态保留前两字符（省份简称 + 发牌机关代号）+ 固定 5 星号尾 */
    @Override
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        var matcher = pattern().matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String candidate = matcher.group();
            matcher.appendReplacement(sb,
                java.util.regex.Matcher.quoteReplacement(candidate.substring(0, 2) + "*****"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
