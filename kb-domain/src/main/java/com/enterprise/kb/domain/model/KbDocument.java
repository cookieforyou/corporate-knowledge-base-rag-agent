package com.enterprise.kb.domain.model;

import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.DocumentStatus;
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

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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
