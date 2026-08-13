package com.enterprise.kb.domain.enums;

public enum DocumentStatus {
    UPLOADING,
    PARSING,
    /** 增量重入库中（簇⑥ C1）：reparse/replace 原子占用，与 PARSING 同为处理中语义 */
    REINDEXING,
    SUCCESS,
    FAILED;

    /**
     * 处理期判定（簇⑥ C1 收尾删除守卫）：后端删除守卫与前端按钮 disable
     * （Documents.vue isLiveDocStatus）共用同一集合，避免两侧口径漂移。
     */
    public boolean isProcessing() {
        return this == UPLOADING || this == PARSING || this == REINDEXING;
    }
}
