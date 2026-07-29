package com.enterprise.kb.etl.pipeline;

/**
 * ETL 处理阶段
 */
public enum EtlStage {
    READING,
    TRANSFORMING,
    PERSISTING,
    EMBEDDING,
    COMPLETED,
    FAILED
}
