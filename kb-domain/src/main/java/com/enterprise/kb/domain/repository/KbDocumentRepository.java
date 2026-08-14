package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface KbDocumentRepository extends JpaRepository<KbDocument, String> {

    List<KbDocument> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    // ── 统计聚合（Phase 4 簇② 任务 4.6：运维仪表盘数据接口）──

    long countByTenantId(String tenantId);

    /** 按解析状态分组计数：返回 [DocumentStatus, count] 行集 */
    @Query("SELECT d.status, COUNT(d) FROM KbDocument d WHERE d.tenantId = :tenantId GROUP BY d.status")
    List<Object[]> countGroupByStatus(@Param("tenantId") String tenantId);

    /** 按解析路由分组计数：返回 [parseRoute, count] 行集（路由为 null 的未解析文档归入 null 行） */
    @Query("SELECT d.parseRoute, COUNT(d) FROM KbDocument d WHERE d.tenantId = :tenantId GROUP BY d.parseRoute")
    List<Object[]> countGroupByParseRoute(@Param("tenantId") String tenantId);

    /**
     * 入库趋势（按创建日聚合）：返回 [LocalDate, 文档数, chunk 数] 行集。
     * chunk 数取 kb_document.chunk_count 求和（重入库成功后回写，口径 = 存活文档切片规模；
     * 软删 chunk 的精确口径属簇③ 运维域，仪表盘取文档侧近似即可）。
     */
    @Query("SELECT CAST(d.createdAt AS LocalDate), COUNT(d), SUM(COALESCE(d.chunkCount, 0)) "
        + "FROM KbDocument d WHERE d.tenantId = :tenantId AND d.createdAt >= :since "
        + "GROUP BY CAST(d.createdAt AS LocalDate)")
    List<Object[]> dailyIngestion(@Param("tenantId") String tenantId, @Param("since") LocalDateTime since);

    /** 指定状态文档的 chunk 总数（chunk_count 空值计 0；无匹配文档返回 0） */
    @Query("SELECT COALESCE(SUM(COALESCE(d.chunkCount, 0)), 0) FROM KbDocument d "
        + "WHERE d.tenantId = :tenantId AND d.status = :status")
    long sumChunkCountByStatus(@Param("tenantId") String tenantId, @Param("status") DocumentStatus status);

    /** 处理中文档列表（运维仪表盘解析状态面板，按更新时间倒序） */
    List<KbDocument> findByTenantIdAndStatusInOrderByUpdatedAtDesc(String tenantId, List<DocumentStatus> statuses);

    /**
     * 增量重入库原子占用（簇⑥ C1）：仅 SUCCESS/FAILED 态可被占用为 REINDEXING，
     * 返回影响行数——0 = 状态已被并发占用或不可重入库（调用方据此返回 DOC_NOT_READY）。
     *
     * <p>DB 级 check-then-act 原子化：单条 UPDATE ... WHERE status IN (...) 消除
     * 双请求竞态双占用，零 Redis 依赖（与 3.17 SETNX 守卫互补的另一种守卫形态）。
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE KbDocument d SET d.status = :reindexing "
        + "WHERE d.id = :id AND d.status IN :acquirable")
    int acquireForReindex(@Param("id") String id,
                          @Param("reindexing") DocumentStatus reindexing,
                          @Param("acquirable") List<DocumentStatus> acquirable);
}
