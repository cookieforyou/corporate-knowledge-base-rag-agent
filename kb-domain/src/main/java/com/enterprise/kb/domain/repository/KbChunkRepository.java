package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbChunkRepository extends JpaRepository<KbChunk, String> {

    List<KbChunk> findByDocIdOrderByChunkIndex(String docId);

    List<KbChunk> findByVectorIdIn(List<String> vectorIds);

    /**
     * 租户存活 chunk 精确计数（Phase 4 簇③）：JOIN 文档租户维度 + 排除软删
     * （is_deleted=true）。统计总览 chunkTotal 的精确口径——簇② 4.6 曾取
     * 文档侧 chunk_count 近似（软删不扣减），软删门面（4.4）生效后切换至此。
     */
    @Query("SELECT COUNT(c) FROM KbChunk c, KbDocument d "
        + "WHERE c.docId = d.id AND d.tenantId = :tenantId AND c.isDeleted = false")
    long countAliveByTenantId(@Param("tenantId") String tenantId);
}
