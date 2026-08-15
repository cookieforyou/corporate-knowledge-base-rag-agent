package com.enterprise.kb.domain.enums;

/**
 * Bad Case 根因四分类（Phase 4 簇④ 4.7）——人工标注于 kb_audit_log.root_cause，
 * 作为 Golden Set 回灌与检索/生成侧改进的方向性依据。
 */
public enum RootCause {

    /** 检索未命中：目标证据未进入 final 序列（切分/向量/BM25/重排任一环节丢失） */
    RETRIEVAL_MISS,

    /** 改写漂移：多轮压缩/查询改写偏离原意，检索方向错误 */
    REWRITE_DRIFT,

    /** 生成幻觉：证据命中且正确，模型回答编造或偏离证据 */
    HALLUCINATION,

    /** 解析不足：文档解析/切分质量缺陷导致证据本身缺失（表格丢失/乱码/截断） */
    PARSING_GAP
}
