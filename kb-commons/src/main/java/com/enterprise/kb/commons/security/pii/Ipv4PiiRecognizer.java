package com.enterprise.kb.commons.security.pii;

import java.util.regex.Pattern;

/**
 * IPv4 地址识别器（安全簇③ C1 新增）：四段 0-255 点分十进制，段值范围校验
 * （25[0-5]/2[0-4]x/1xx/[1-9]?x 分支）+ 前后数字与点边界断言（防五段点分串
 * 与更长数字点分形态内部误匹配）。
 *
 * <p><b>已知边界</b>（L1 启发式，登记留观察）：紧邻字母前缀的四段版本字符串
 * （如 v1.2.3.4 形态）会被匹配——段值校验与边界断言仅覆盖数字/点邻接面；
 * 误报治理经类型开关 {@code rag.guardrail.pii.ipv4.enabled} 运维侧绕开。
 */
public final class Ipv4PiiRecognizer extends RegexPiiRecognizer {

    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

    private static final Pattern PATTERN = Pattern.compile(
        "(?<![\\d.])" + OCTET + "\\." + OCTET + "\\." + OCTET + "\\." + OCTET + "(?![\\d.])");

    private static final String MASK = "***.***.***.***";

    public Ipv4PiiRecognizer() {
        super(PiiType.IPV4, 1.0, PATTERN, MASK);
    }
}
