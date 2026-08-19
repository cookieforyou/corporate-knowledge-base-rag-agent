package com.enterprise.kb.commons.guardrail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 护栏词表运营支撑工具（v2.53 词表 DB 单轨）：指纹 / 编码态转换。
 *
 * <p><b>指纹语义</b>（与加载层运行时语义一致）：SHA-256(规范化后解码值)——
 * KEYWORD 为小写化后值、REGEX 为模式源文，由调用方在规范化后调用；
 * 对齐带外通道 import_words.py「解码值指纹 + 侧 + 类型」幂等去重口径
 * （{@code uk_gr_dedup(side, type, fingerprint)} 唯一约束的消费侧）。
 */
public final class GuardrailRulesSupport {

    private GuardrailRulesSupport() {
    }

    /** SHA-256 十六进制全量（64 位）——DB 去重键指纹。 */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 运行时明文 → Base64 编码态（加载层解码的逆操作，交付形态约束）。 */
    public static String encodeB64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Base64 编码态 → 运行时明文；非法编码抛 IllegalArgumentException（调用方校验承接）。 */
    public static String decodeB64(String encoded) {
        return new String(java.util.Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
    }
}
