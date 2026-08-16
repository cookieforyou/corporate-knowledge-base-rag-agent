package com.enterprise.kb.commons.guardrail;

/**
 * 注入攻击族系七分法（安全簇① A1，设计 12.7；参照 OWASP LLM01 缓解域与失效分类学）。
 *
 * <p><b>中性命名纪律</b>（第七节敏感词交付纪律条 3）：枚举名不含攻击语义字面，
 * 仅以族系语义命名。
 *
 * <p><b>与 kb-eval {@code AttackType} 的区分</b>：{@code AttackType}
 * （DIRECT / ENCODING_BYPASS / JAILBREAK / MULTILINGUAL）是<b>攻击样本侧</b>族系，
 * 描述语料样本的攻击手法；本枚举是<b>词表词项侧</b>族系，描述被检测词项归属的攻击族。
 * 两套族系语义不同、各自独立，JAILBREAK / MULTILINGUAL 同名但分属不同枚举类型，
 * 样本与词项的 family 映射关系词表运营期定案。
 */
public enum GuardrailFamily {

    /** 指令覆盖族：要求模型忽略/覆盖既有指令 */
    INSTRUCTION_OVERRIDE,

    /** 角色劫持族：诱导模型切换/扮演越权角色 */
    ROLE_HIJACK,

    /** 敏感信息套取族：诱导输出系统提示词/内部配置等 */
    INFO_EXTRACTION,

    /** 编码混淆族：以编码/变形手段绕过字面匹配 */
    ENCODING_OBFUSCATION,

    /** 多语种族：以非主语种表达规避检测 */
    MULTILINGUAL,

    /** 越狱引导族：引导突破安全策略 */
    JAILBREAK,

    /** 工具诱导族：诱导越权调用工具/执行操作 */
    TOOL_INDUCED,

    /** 兼容档：存量迁移未标注族系 / legacy CSV 词条的过渡归属，词表运营期转正式族系 */
    UNCLASSIFIED
}
