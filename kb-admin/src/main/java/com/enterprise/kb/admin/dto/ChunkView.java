package com.enterprise.kb.admin.dto;

import com.enterprise.kb.domain.model.KbChunk;

import java.time.LocalDateTime;

/** Chunk 运维视图（4.4）——脱去 JSONB/向量内部字段的运维面投影 */
public record ChunkView(
    String id,
    String docId,
    Integer chunkIndex,
    String content,
    String chunkType,
    Integer pageNum,
    boolean isDeleted,
    String headingPath,
    LocalDateTime updatedAt) {

    public static ChunkView from(KbChunk chunk) {
        return new ChunkView(
            chunk.getId(),
            chunk.getDocId(),
            chunk.getChunkIndex(),
            chunk.getContent(),
            chunk.getChunkType() != null ? chunk.getChunkType().name() : null,
            chunk.getPageNum(),
            Boolean.TRUE.equals(chunk.getIsDeleted()),
            chunk.getHeadingPath(),
            chunk.getUpdatedAt());
    }
}
