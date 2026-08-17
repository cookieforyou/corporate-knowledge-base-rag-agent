package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * 银行卡号识别器（安全簇③ C1 新增）：13-19 位数字（主流卡组织 16-19 位），
 * 分隔符容忍（数字间空格/连字符，G2 拆词防护同手机形态）+ 边界断言
 * （20+ 位长数字串不匹配，防订单号/账号误报）+ <b>Luhn 校验</b>防长数字串
 * 误报（专项方案 §3.5 定案，延续 v2.4 边界断言纪律）。
 *
 * <p><b>与身份证的交叠口径</b>：注册表顺序 PHONE → ID_CARD → EMAIL → BANK_CARD，
 * 18 位纯数字串先落身份证掩码（既有顺序语义保留）；19 位串身份证模式不可达
 * （前瞻断言阻断），由本识别器 Luhn 判定。
 */
public final class BankCardPiiRecognizer extends RegexPiiRecognizer {

    /** 候选形态：13-19 位数字（数字间允许空格/连字符），Luhn 校验在候选后过滤 */
    private static final Pattern PATTERN =
        Pattern.compile("(?<!\\d)\\d(?:[ \\-]?\\d){12,18}(?!\\d)");

    private static final String MASK = "****-****-****-****";

    public BankCardPiiRecognizer() {
        super(PiiType.BANK_CARD, 1.0, PATTERN, MASK);
    }

    /** 候选后过滤：剥离分隔符后须为 13-19 位纯数字且 Luhn 校验通过 */
    @Override
    protected boolean candidateValid(String candidate) {
        return luhnValid(stripSeparators(candidate));
    }

    private static String stripSeparators(String candidate) {
        return candidate.replace(" ", "").replace("-", "");
    }

    /**
     * Luhn 校验（ISO/IEC 7812-1）：从右向左隔位加倍（>9 减 9）求和，模 10 归零。
     * 仅接受 13-19 位纯数字串（长度越界直接否决，双保险）。
     */
    static boolean luhnValid(String digits) {
        if (digits == null || digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleNext = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            int d = c - '0';
            if (doubleNext) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleNext = !doubleNext;
        }
        return sum % 10 == 0;
    }
}
