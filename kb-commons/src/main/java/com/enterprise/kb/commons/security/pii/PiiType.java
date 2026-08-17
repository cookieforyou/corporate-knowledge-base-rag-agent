package com.enterprise.kb.commons.security.pii;

/**
 * PII 类型枚举（安全簇③ C1/C2，设计 12 章 PII 识别器注册表）——识别器注册表的
 * 类型词汇表，对齐 Presidio 识别器注册表语义（每类型独立识别器）。
 *
 * <p>前七类为确定性正则可判定类型（C1 扩容后全集）；{@link #NAME}/{@link #ADDRESS}
 * 为 C3 登记项——NER 依赖、误报管理成本高，默认关闭且识别器未实现，配置开关
 * 预留（{@code rag.guardrail.pii.name/address.enabled}），待干净集误报度量后再评估。
 */
public enum PiiType {

    /** 手机号：1[3-9] 开头 11 位，分隔符容忍 + 边界断言 */
    PHONE,

    /** 身份证号：18 位（末位可 X），分隔符容忍 + 边界断言 */
    ID_CARD,

    /** 邮箱地址 */
    EMAIL,

    /** 银行卡号：13-19 位数字，Luhn 校验 + 分隔符容忍 + 边界断言（C1 新增） */
    BANK_CARD,

    /** 座机号：0 开头区号 + 7-8 位号码，分隔符容忍 + 边界断言（C1 新增） */
    LANDLINE,

    /** 车牌号：省份汉字 + 发牌机关代号 + 序号（含新能源 6 位形态，C1 新增） */
    LICENSE_PLATE,

    /** IPv4 地址：四段 0-255 点分十进制，段值校验 + 边界断言（C1 新增） */
    IPV4,

    /** 姓名（C3 登记：NER 依赖，识别器未实现，开关预留默认关） */
    NAME,

    /** 地址（C3 登记：NER 依赖，识别器未实现，开关预留默认关） */
    ADDRESS
}
