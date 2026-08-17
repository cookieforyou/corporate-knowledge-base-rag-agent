package com.enterprise.kb.commons.security.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则型 PII 识别器基类（安全簇③ C2）：预编译模式 + 固定掩码字面的通用实现。
 *
 * <p>子类只需提供类型/置信度/模式/掩码字面四元组；需要校验后过滤（银行卡 Luhn）
 * 或动态掩码（车牌保留前缀）的识别器覆写对应钩子。
 */
public abstract class RegexPiiRecognizer implements PiiRecognizer {

    private final PiiType type;
    private final double confidence;
    private final Pattern pattern;
    private final String maskLiteral;

    protected RegexPiiRecognizer(PiiType type, double confidence, Pattern pattern, String maskLiteral) {
        this.type = type;
        this.confidence = confidence;
        this.pattern = pattern;
        this.maskLiteral = maskLiteral;
    }

    @Override
    public PiiType type() {
        return type;
    }

    @Override
    public double confidence() {
        return confidence;
    }

    protected Pattern pattern() {
        return pattern;
    }

    protected String maskLiteral() {
        return maskLiteral;
    }

    /** 候选匹配谓词：缺省全收；校验型识别器（Luhn）覆写过滤 */
    protected boolean candidateValid(String candidate) {
        return true;
    }

    @Override
    public List<PiiHit> detect(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Matcher matcher = pattern.matcher(text);
        List<PiiHit> hits = new ArrayList<>();
        while (matcher.find()) {
            if (candidateValid(matcher.group())) {
                hits.add(new PiiHit(type, matcher.start(), matcher.end(), confidence));
            }
        }
        return List.copyOf(hits);
    }

    @Override
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement = candidateValid(matcher.group())
                ? maskLiteral
                : Matcher.quoteReplacement(matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
