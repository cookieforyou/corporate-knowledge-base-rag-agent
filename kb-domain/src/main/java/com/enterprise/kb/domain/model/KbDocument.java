package com.enterprise.kb.domain.model;

import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.GraphStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档主表实体（Phase 1 最小字段集）
 */
@Data
@Entity
@Table(name = "kb_document")
public class KbDocument {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", length = 36, nullable = false)
    private String tenantId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "original_name", length = 500)
    private String originalName;

    @Column(name = "type", length = 20, nullable = false)
    private String type;

    @Column(name = "size")
    private Long size;

    @Column(name = "oss_path", length = 500)
    private String ossPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private DocumentStatus status = DocumentStatus.UPLOADING;

    @Column(name = "parse_route", length = 20)
    private String parseRoute;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "table_count")
    private Integer tableCount;

    @Column(name = "image_count")
    private Integer imageCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * 文档版本号（簇⑥ C1）：首次入库 = 1，每次增量重入库成功 +1。
     * 用途：运维审计追溯 + 前端版本展示；[ref-N] 引用经 docId 定位文档，
     * 重入库不碎引用（引用指向文档而非特定版本 chunk）。
     */
    @Column(name = "version")
    private Integer version = 1;

    /**
     * 图谱构建状态（Phase 5 簇④，V2 迁移）：抽取是 ETL 成功后的异步旁路，
     * 独立于文档入库主状态追踪图覆盖形态；回填任务按 PENDING/FAILED 选目标。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "graph_status", length = 20)
    private GraphStatus graphStatus = GraphStatus.PENDING;

    /** 最近一次图谱抽取完成/失败时间（运维观测与回填选目标用） */
    @Column(name = "graph_updated_at")
    private LocalDateTime graphUpdatedAt;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
