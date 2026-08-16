package com.enterprise.kb.commons.guardrail;

/**
 * 护栏词项匹配类型（安全簇① A1，设计 12.7）。
 */
public enum RuleType {

    /** 关键词子串匹配（大小写不敏感，作用在归一化检测视图上） */
    KEYWORD,

    /**
     * 结构化正则模式匹配（A2 REGEX 轨）：以「动词×宾语」组合句式与编码特征模式
     * 代替字面样本枚举，扩面同时天然遵守第七节敏感词交付纪律。
     * 模式编译为大小写不敏感。
     */
    REGEX
}
