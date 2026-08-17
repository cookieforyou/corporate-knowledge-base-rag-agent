package com.enterprise.kb.commons.security.pii;

/**
 * PII 识别结果（安全簇③ C2，对齐 Presidio RecognizerResult 语义）：
 * 命中位置区间 + 类型 + 置信度。
 *
 * <p><b>不携带命中文本值</b>——识别结果对象在对象图中流转（审计/日志旁路），
 * 只记事实（类型/位置/置信度）不落原文，与护栏观测面「只记事实不落原文」纪律同源。
 *
 * @param type       命中的 PII 类型
 * @param start      命中区间起始下标（含）
 * @param end        命中区间结束下标（不含）
 * @param confidence 识别置信度（形态确定度，对齐 Presidio confidence 槽位，
 *                   确定性正则 1.0，启发式形态按确定度降档；预留未来优先级仲裁消费）
 */
public record PiiHit(PiiType type, int start, int end, double confidence) {
}
