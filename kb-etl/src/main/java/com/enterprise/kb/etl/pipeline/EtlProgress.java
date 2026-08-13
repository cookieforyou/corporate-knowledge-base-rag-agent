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
        this.percentage = stageBaseline(stage);
    }

    /** 阶段基线百分比（前端进度条骨架；EMBEDDING 阶段可由 chunk 处理数细化） */
    public static double stageBaseline(EtlStage stage) {
        return switch (stage) {
            case READING -> 5;
            case TRANSFORMING -> 15;
            case PERSISTING -> 30;
            case EMBEDDING -> 50;
            case INDEXING -> 85;
            case CLEANUP -> 92;
            case COMPLETED -> 100;
            case FAILED -> 0;
        };
    }
}
