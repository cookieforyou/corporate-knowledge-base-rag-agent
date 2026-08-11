package com.enterprise.kb.domain.model;

import com.enterprise.kb.domain.enums.ChunkType;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 切分块表（核心业务表）
 */
@Data
@Entity
@Table(name = "kb_chunk")
public class KbChunk {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "doc_id", length = 36, nullable = false)
    private String docId;

    @Column(name = "section_id", length = 36)
    private String sectionId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "original_content", columnDefinition = "TEXT")
    private String originalContent;

    @Column(name = "page_num")
    private Integer pageNum;

    @Column(name = "token_count")
    private Integer tokenCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "JSONB")
    private String metadata = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", length = 20)
    private ChunkType chunkType = ChunkType.TEXT;

    @Column(name = "vector_id", length = 100)
    private String vectorId;

    @Column(name = "is_deleted")
    private Boolean isDeleted = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 标题路径（簇④ A4）——ETL 管道内载体字段，**非持久化列**（免 ECS ALTER）。
     * 持久化面：kb_chunk.metadata JSONB 的 heading_path 键；消费面：向量库元数据与
     * ES heading_path 字段（展示/检索两用，9.2 v2.21）。
     */
    @Transient
    private String headingPath;

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
