package com.enterprise.kb.commons.security;

import com.enterprise.kb.commons.guardrail.GuardrailRule;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本安全消毒公共组件（簇② B1，设计文档 12.4 S1/S4 + PII 入库消毒）
 *
 * <p>对话链路（{@code InputSanitizeAdvisor}）与 ETL 入库链路
 * （{@code SanitizingTransformer}）的同源实现：PII 掩码正则、归一化规则、
 * 结构化词表匹配入口集中于此，两处护栏永不漂移。注入词表本体收编
 * {@code guardrail} 包（结构化文件 + 加载层解码，安全簇① T2 起本类不再
 * 持有字面词表）。
 *
 * <p><b>S1 归一化</b>（防 G2 编码绕过）：零宽字符剥离 → NFKC 归一（全角→半角、
 * 兼容形式还原）→ 空白折叠。全角「ｉｇｎｏｒｅ ｐｒｅｖｉｏｕｓ」、零宽字符拆词、
 * 多空白拆词在归一化后均现出原形被正则捕获。
 *
 * <p><b>PII 掩码</b>：手机/身份证正则允许数字间夹空格与连字符
 * （G2：{@code 138 1234 5678} 拆词绕过），掩码幂等（掩码形态不会被二次匹配）。
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    // ── 归一化（S1）──

    /** 零宽/不可见字符：ZWSP/ZWNJ/ZWJ/词连接符/BOM/软连字符/蒙古文元音分隔符 */
    private static final Pattern INVISIBLE_CHARS =
        Pattern.compile("[\u200B\u200C\u200D\u2060\uFEFF\u00AD\u180E]");

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** 零宽/不可见字符剥离（null 安全）——PII 掩码前调用，防零宽拆断数字串 */
    public static String stripInvisible(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return INVISIBLE_CHARS.matcher(text).replaceAll("");
    }

    /**
     * 归一化检测视图：零宽字符剥离 → NFKC → 连续空白折叠为单空格（null 安全）。
     *
     * <p><b>仅用于注入检测，不回写用户输入</b>——NFKC 同时归一全角标点
     * （「？」→「?」），回写会改变正常中文查询形态；掩码落原文（容忍分隔符
     * 的正则已覆盖 G2 空格/连字符拆词）。
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = stripInvisible(text);
        result = Normalizer.normalize(result, Normalizer.Form.NFKC);
        return WHITESPACE_RUN.matcher(result).replaceAll(" ");
    }

    // ── PII 掩码 ──

    /** 手机号：1[3-9] 开头 11 位，数字间允许空格/连字符（边界断言防长数字串内部误匹配） */
    private static final Pattern PHONE_PATTERN =
        Pattern.compile("(?<!\\d)1[3-9](?:[ \\-]?\\d){9}(?!\\d)");

    /** 身份证号：18 位（末位可 X），数字间允许空格/连字符 */
    private static final Pattern ID_CARD_PATTERN =
        Pattern.compile("(?<!\\d)\\d(?:[ \\-]?\\d){16}[ \\-]?[\\dXx](?![\\dXx])");

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}");

    private static final String PHONE_MASK = "1***-****-****";
    private static final String ID_CARD_MASK = "******************";
    private static final String EMAIL_MASK = "***@***.***";

    /**
     * PII 掩码（幂等）：手机号/身份证/邮箱三类。对话链路保护模型上下文与记忆；
     * ETL 链路落库前对 chunk 内容同规则消毒（kb_chunk/向量库/ES 均存脱敏态）。
     */
    public static String maskPii(String text) {
        if (text == null) {
            return null;
        }
        String result = PHONE_PATTERN.matcher(text).replaceAll(PHONE_MASK);
        result = ID_CARD_PATTERN.matcher(result).replaceAll(ID_CARD_MASK);
        result = EMAIL_PATTERN.matcher(result).replaceAll(EMAIL_MASK);
        return result;
    }

    // ── 注入词表匹配（L1 词表防域，12.1/12.7）──
    // 词表本体：classpath guardrail/injection-rules.yml（逐条 Base64 编码态，
    // GuardrailRulesLoader 加载层解码）——安全簇① T2 起硬编码字面词表已退役。

    /**
     * 结构化词表匹配（安全簇① A1）：返回命中的启用词项列表（含族系/动作元数据），
     * 由调用方按 {@code action} 解释（BLOCK 拒绝 / FLAG 观察）。调用方应先以
     * {@link #normalize} 构造归一化检测视图传入；KEYWORD 大小写不敏感子串、
     * REGEX 预编译模式 find，匹配语义收编 {@link GuardrailRule#matches}。
     */
    public static List<GuardrailRule> matchRules(String normalizedText, List<GuardrailRule> rules) {
        if (normalizedText == null || normalizedText.isEmpty() || rules == null || rules.isEmpty()) {
            return List.of();
        }
        return rules.stream().filter(rule -> rule.matches(normalizedText)).toList();
    }
}
