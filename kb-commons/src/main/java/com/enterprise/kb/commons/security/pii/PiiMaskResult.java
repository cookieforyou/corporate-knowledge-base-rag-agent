package com.enterprise.kb.commons.security.pii;

import java.util.Set;

/**
 * PII 掩码结果（安全簇③ C2）：掩码后文本 + 命中的类型集合——掩码视图的
 * 报告形态，供消费侧按类型记录指标子项（对话链 InputSanitizeAdvisor），
 * 纯掩码消费侧（ETL/审计）走 {@link PiiRecognizerRegistry#mask} 不报告形态。
 *
 * @param text     掩码后文本（入参 null 时为 null，与既有 maskPii 语义一致）
 * @param hitTypes 命中的 PII 类型集合（按枚举自然序的不可变集；无命中为空集）
 */
public record PiiMaskResult(String text, Set<PiiType> hitTypes) {
}
