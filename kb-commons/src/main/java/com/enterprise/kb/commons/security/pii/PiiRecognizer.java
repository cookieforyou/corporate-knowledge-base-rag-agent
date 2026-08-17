package com.enterprise.kb.commons.security.pii;

import java.util.List;

/**
 * PII 识别器契约（安全簇③ C2，设计 12 章 PII 识别器注册表）——对齐 Presidio
 * 识别器语义（识别器注册表：正则/词表/NER 三类识别器 + 置信度），Java 原生自建
 * 不引 Python 依赖（专项方案 §3.5 调研定案）。
 *
 * <p>每类型一个独立识别器：检测（{@link #detect}，返回命中区间）与掩码
 * （{@link #mask}，幂等整段替换）两个视图——对话链/ETL 入库消费掩码视图，
 * 输出侧 PII 回显探测消费检测视图（观察起步不替换）。
 *
 * <p><b>实现纪律</b>：① 掩码幂等——掩码形态不会被自身或后续识别器二次匹配
 * （掩码字面不含可再匹配的数字/结构）；② 数字类模式带边界断言防长数字串内部
 * 误匹配（v2.4 纪律延续）；③ 识别器无状态线程安全（模式预编译，启动期单例装配）。
 */
public interface PiiRecognizer {

    /** 识别的 PII 类型（注册表内唯一） */
    PiiType type();

    /**
     * 识别置信度（形态确定度）：确定性正则 1.0，启发式形态按确定度降档。
     * 对齐 Presidio confidence 语义，现阶段为元数据（优先级仲裁预留）。
     */
    double confidence();

    /**
     * 检测视图文本中的 PII 命中（null/空安全，返回不可变列表，按出现顺序）。
     * 只记位置与类型，不携带命中文本值（观测面不落原文纪律）。
     */
    List<PiiHit> detect(String text);

    /** PII 掩码（幂等，null 安全）：命中区间替换为该类型掩码形态 */
    String mask(String text);
}
