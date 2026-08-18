package com.enterprise.kb.ai.advisor;

/**
 * L2 语义判定结构化输出（安全簇⑤ E1，SemanticInjectionAdvisor 消费）
 *
 * <p>经 {@code ChatClient.call().entity(L2Verdict.class)} 解析——
 * jsonschema-module-jackson（父 POM 锁定 5.0.0）由 record 生成 JSON Schema
 * 注入判定 prompt。verdict 用 String 承接而非 enum：模型输出变体
 * （大小写/空白）由消费方 equalsIgnoreCase 容错，避免反序列化硬失败
 * （IntentResult 同款形态，坑位⑫先例延续）。
 *
 * @param verdict PASS（正常业务查询放行）| SUSPECT（可疑证据不足，FLAG 计数放行）|
 *                BLOCK（明确攻击意图，PROMPT_INJECTION 同语义拒答）
 * @param family  命中攻击族系名（GuardrailFamily 七分法中性枚举名，BLOCK/SUSPECT
 *                时供 FLAG 计数与审计标记；PASS/未知时忽略）
 */
public record L2Verdict(String verdict, String family) {

    public static final String VERDICT_PASS = "PASS";
    public static final String VERDICT_SUSPECT = "SUSPECT";
    public static final String VERDICT_BLOCK = "BLOCK";
}
