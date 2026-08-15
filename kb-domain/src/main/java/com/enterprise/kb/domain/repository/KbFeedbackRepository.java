package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbFeedbackRepository extends JpaRepository<KbFeedback, String>,
    JpaSpecificationExecutor<KbFeedback> {

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

    // 3.17 Bad Case 租户域查询经 FeedbackSpecs.tenantFeedback(...) + findAll(spec, pageable)
    // 执行——原 @Query 可选参数形态同源 PG 预编译类型推断缺陷，v2.35 修正
}
