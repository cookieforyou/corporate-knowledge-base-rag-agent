package com.enterprise.kb.commons.security;

import com.enterprise.kb.commons.guardrail.GuardrailRule;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本安全消毒公共组件（簇② B1，设计文档 12.4 S1/S4）
 *
 * <p>对话链路（{@code InputSanitizeAdvisor}）与 ETL 入库链路
 * （{@code SanitizingTransformer}）的同源实现：归一化规则与结构化词表匹配入口
 * 集中于此，两处护栏永不漂移。注入词表本体收编 {@code guardrail} 包
 * （结构化文件 + 加载层解码，安全簇① T2 起本类不再持有字面词表）。
 *
 * <p><b>PII 能力迁移</b>（安全簇③ C2）：PII 掩码由静态正则演进为
 * {@code pii} 包识别器注册表形态（{@code PiiRecognizerRegistry}，对齐 Presidio
 * 语义）——单一实现源纪律的承载体由本类迁至注册表 Bean，消费方经 Spring
 * 注入同一 Bean；{@code stripInvisible} 保留供对话链掩码前零宽剥离。
 *
 * <p><b>S1 归一化</b>（防 G2 编码绕过）：零宽字符剥离 → NFKC 归一（全角→半角、
 * 兼容形式还原）→ 空白折叠。全角「ｉｇｎｏｒｅ ｐｒｅｖｉｏｕｓ」、零宽字符拆词、
 * 多空白拆词在归一化后均现出原形被正则捕获。
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

    // ── PII 掩码能力已迁 pii 包识别器注册表（安全簇③ C2）──
    // PiiRecognizerRegistry：类型扩容（C1 银行卡/座机/车牌/IPv4）+ 每类型独立
    // 识别器（模式/置信度/掩码策略/enabled 开关）+ 检测/掩码双视图。

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
