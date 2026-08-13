package com.enterprise.kb.domain.enums;

public enum DocumentStatus {
    UPLOADING,
    PARSING,
    /** 增量重入库中（簇⑥ C1）：reparse/replace 原子占用，与 PARSING 同为处理中语义 */
    REINDEXING,
    SUCCESS,
    FAILED
}
