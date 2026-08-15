package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface KbAuditLogRepository extends JpaRepository<KbAuditLog, Long> {

    List<KbAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<KbAuditLog> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 3.17 反馈关联：trace_id 唯一标识一轮问答，反馈 API 凭此确定性定位审计行回填 feedback */
    Optional<KbAuditLog> findFirstByTraceId(String traceId);

    /**
     * Bad Case 运营查询（Phase 4 簇④ 4.7）：租户域内审计日志多选项过滤。
     *
     * <p>过滤项均传 null 不过滤：时间窗（from/to 闭区间）/ userId / sessionId /
     * feedback（POSITIVE/NEGATIVE）/ status（SUCCESS/REJECTED/ERROR）/
     * rootCause（已标注分类）/ annotated（true=已标注，false=未标注）。
     * 租户过滤恒在（fail-closed 纪律），按 created_at 倒序分页。
     */
    @Query("""
        SELECT a FROM KbAuditLog a
        WHERE a.tenantId = :tenantId
          AND (:from IS NULL OR a.createdAt >= :from)
          AND (:to IS NULL OR a.createdAt <= :to)
          AND (:userId IS NULL OR a.userId = :userId)
          AND (:sessionId IS NULL OR a.sessionId = :sessionId)
          AND (:feedback IS NULL OR a.feedback = :feedback)
          AND (:status IS NULL OR a.status = :status)
          AND (:rootCause IS NULL OR a.rootCause = :rootCause)
          AND (:annotated IS NULL
               OR (:annotated = true AND a.rootCause IS NOT NULL)
               OR (:annotated = false AND a.rootCause IS NULL))
        ORDER BY a.createdAt DESC
        """)
    Page<KbAuditLog> search(@Param("tenantId") String tenantId,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to,
                            @Param("userId") String userId,
                            @Param("sessionId") String sessionId,
                            @Param("feedback") String feedback,
                            @Param("status") String status,
                            @Param("rootCause") String rootCause,
                            @Param("annotated") Boolean annotated,
                            Pageable pageable);
}
