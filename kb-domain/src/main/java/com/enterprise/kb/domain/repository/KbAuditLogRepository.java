package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KbAuditLogRepository extends JpaRepository<KbAuditLog, Long> {

    List<KbAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<KbAuditLog> findByUserIdOrderByCreatedAtDesc(String userId);
}
