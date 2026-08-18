package com.enterprise.kb.admin.dto;

import java.util.List;

/**
 * 命中演练结果（安全簇⑥ F2）：输入文本经归一化检测视图后，注入/输出
 * 双侧词表的命中词项元数据清单（{@link GuardrailRuleView} 形态，value 不回显）。
 * 判定口径与运行时一致（{@code TextSanitizer.normalize + matchRules}），
 * 供「FLAG→BLOCK 转档」与热重载即时性验证消费。
 */
public record DrillResult(
    List<GuardrailRuleView> injectionMatches,
    List<GuardrailRuleView> outputMatches) {
}
