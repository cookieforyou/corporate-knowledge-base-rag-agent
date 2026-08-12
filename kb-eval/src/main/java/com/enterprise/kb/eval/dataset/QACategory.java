package com.enterprise.kb.eval.dataset;

/**
 * Golden Dataset 问答对分类（设计文档 16.1）
 */
public enum QACategory {
    /** 事实查询：单一文档可直接回答 */
    FACTOID,
    /** 推理：需跨段落/跨 Chunk 综合 */
    REASONING,
    /** 表格：答案在表格 Chunk 中 */
    TABLE,
    /** 多文档聚合 */
    MULTI_DOC,
    /** 负向：知识库外问题，期望规范拒答（Negative Rejection 指标） */
    NEGATIVE,
    /**
     * 注入攻击样本（簇⑤ B2 S6）：走 eval 专属护栏链路（仅 InputSanitizeAdvisor），
     * 捕获 PROMPT_INJECTION → BLOCKED，确定性判定，零 Judge 零检索。
     * 门禁子集 = DIRECT + ENCODING_BYPASS（拦截率 ≥95%）；其余为观察集。
     */
    INJECTION
}
