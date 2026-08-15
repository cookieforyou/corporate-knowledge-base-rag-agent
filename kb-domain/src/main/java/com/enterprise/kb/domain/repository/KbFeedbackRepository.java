package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbFeedback;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbFeedbackRepository extends JpaRepository<KbFeedback, String> {

    List<KbFeedback> findByMessageId(String messageId);

    /** 3.17 upsert 幂等键：同一用户对同一回答仅一条反馈（可更改评价） */
    Optional<KbFeedback> findByMessageIdAndUserId(String messageId, String userId);

    /** 簇④ 4.7：审计行关联反馈（一轮问答至多一条，audit_log_id 经 3.17 回填） */
    Optional<KbFeedback> findFirstByAuditLogId(Long auditLogId);

    /** 簇④ 4.7：审计查询页联查期望回答（批量，避免 N+1） */
    List<KbFeedback> findByAuditLogIdIn(List<Long> auditLogIds);

    /** 历史消息反馈回显：批量查当前用户对一组消息的既有评价（至多每消息一条） */
    List<KbFeedback> findByMessageIdInAndUserId(List<String> messageIds, String userId);

    /**
     * 会话删除前置清理（v2.17.1）：kb_feedback.message_id 外键无级联，
     * 须在删除 kb_message 前清掉该会话全部消息的反馈，否则会话删除外键违例。
     */
    @Modifying
    @Query("""
        DELETE FROM KbFeedback f
        WHERE f.messageId IN (SELECT m.id FROM KbMessage m WHERE m.sessionId = :sessionId)
        """)
    void deleteBySessionId(@Param("sessionId") String sessionId);

    /**
     * 3.17 Bad Case 查询（验收 #13）：kb_feedback 无租户列，经 message→session
     * 两级子查询收敛到租户可见域（租户隔离 fail-closed 纪律）。
     * rating/resolved 传 null 不过滤。
     */
    @Query("""
        SELECT f FROM KbFeedback f
        WHERE f.messageId IN (
            SELECT m.id FROM KbMessage m
            WHERE m.sessionId IN (
                SELECT s.id FROM KbSession s WHERE s.tenantId = :tenantId))
          AND (:rating IS NULL OR CAST(f.rating AS String) = :rating)
          AND (:resolved IS NULL OR f.resolved = :resolved)
        ORDER BY f.createdAt DESC
        """)
    List<KbFeedback> searchTenantFeedback(@Param("tenantId") String tenantId,
                                          @Param("rating") String rating,
                                          @Param("resolved") Boolean resolved,
                                          Pageable pageable);
}
