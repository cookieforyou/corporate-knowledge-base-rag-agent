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
    NEGATIVE
}
