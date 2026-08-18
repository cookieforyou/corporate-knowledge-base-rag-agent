package com.enterprise.kb.commons.guardrail;

import java.util.List;

/**
 * 护栏词表热重载监听器（安全簇⑥ F1，设计 12.7 词表运营 / 12.4 S8）
 *
 * <p>{@link GuardrailRulesRegistry#reload()} 原子替换快照后逐个通知——推模式，
 * 消费方（对话链 Advisor / ETL 消毒转换器）以 volatile 字段承接即完成热切换，
 * 调用侧零改动。双回调 default 空实现：注入侧/输出侧消费方按需各自 override。
 */
public interface GuardrailRulesListener {

    /** 注入侧词表快照已替换（参数为新快照，不可变 List） */
    default void onInjectionRulesUpdated(List<GuardrailRule> rules) {
    }

    /** 输出侧词表快照已替换（参数为新快照，不可变 List） */
    default void onOutputRulesUpdated(List<GuardrailRule> rules) {
    }
}
