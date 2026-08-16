package com.enterprise.kb.commons.guardrail;

/**
 * 护栏词项命中动作（安全簇① A1，设计 12.7）。
 */
public enum RuleAction {

    /** 命中即拒绝：对话链路抛 PROMPT_INJECTION，审计落 REJECTED 三态 */
    BLOCK,

    /**
     * 观察档：命中只计数 + 审计标记，不拒绝。新词先 FLAG 观察、零误伤后转 BLOCK，
     * 是词表扩面的误伤对冲机制（调研 3.2「没有免费午餐」）。指标 rag.guardrail.flagged。
     */
    FLAG
}
