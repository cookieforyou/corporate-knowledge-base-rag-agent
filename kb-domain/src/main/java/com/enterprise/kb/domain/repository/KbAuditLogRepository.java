package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbAuditLogRepository extends JpaRepository<KbAuditLog, Long> {

    List<KbAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<KbAuditLog> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 3.17 反馈关联：trace_id 唯一标识一轮问答，反馈 API 凭此确定性定位审计行回填 feedback */
    Optional<KbAuditLog> findFirstByTraceId(String traceId);
}
