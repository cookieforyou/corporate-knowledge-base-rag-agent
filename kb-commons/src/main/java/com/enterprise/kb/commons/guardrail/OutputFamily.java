package com.enterprise.kb.commons.guardrail;

/**
 * 输出词表三分类（安全簇① A3/T5，设计 12.7 词表工程）——output-rules.yml
 * 词项 {@code family} 字段的分类名词汇表。
 *
 * <p><b>与注入侧 {@link GuardrailFamily} 的区分</b>：七分法是<b>攻击族</b>分类
 * （被检测输入归属的攻击手法族）；本枚举是<b>输出风险分类</b>（被拦截输出内容
 * 的风险域），两套分类语义不同、各自独立，词项按所属侧取对应分类名。
 *
 * <p><b>中性命名纪律</b>（第七节敏感词交付纪律条 3）：分类名不含敏感语义字面。
 */
public enum OutputFamily {

    /** 业务保密：企业内部保密信息（经营数据/内部资料等）外泄风险 */
    BUSINESS_CONFIDENTIAL,

    /** 合规敏感：法规/合规要求限制输出的内容 */
    COMPLIANCE_SENSITIVE,

    /** 竞品对比：涉竞品的倾向性/对比性表述 */
    COMPETITOR_COMPARISON
}
