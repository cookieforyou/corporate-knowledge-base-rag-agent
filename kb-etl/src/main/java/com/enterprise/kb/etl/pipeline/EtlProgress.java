package com.enterprise.kb.etl.pipeline;

import lombok.Data;

/**
 * ETL 进度追踪
 */
@Data
public class EtlProgress {
    private String docId;
    private EtlStage stage;
    private int documentCount;
    private int chunkCount;
    private int processedChunks;
    private double percentage;

    public EtlProgress(String docId, EtlStage stage) {
        this.docId = docId;
        this.stage = stage;
    }
}
