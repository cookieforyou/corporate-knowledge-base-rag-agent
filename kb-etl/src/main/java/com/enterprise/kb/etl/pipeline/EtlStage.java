package com.enterprise.kb.etl.pipeline;

/**
 * ETL 处理阶段
 */
public enum EtlStage {
    READING,
    TRANSFORMING,
    PERSISTING,
    EMBEDDING,
    INDEXING,   // v2（2.5）：ES kb_chunks 双写
    COMPLETED,
    FAILED
}
