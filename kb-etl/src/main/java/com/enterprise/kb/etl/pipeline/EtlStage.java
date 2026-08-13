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
    CLEANUP,    // 簇⑥ C1：蓝绿重入库尾段——三库清理「旧有新无」chunk（首次入库空操作）
    COMPLETED,
    FAILED
}
