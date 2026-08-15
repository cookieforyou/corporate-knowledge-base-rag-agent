package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KbAuditLogRepository extends JpaRepository<KbAuditLog, Long>,
    JpaSpecificationExecutor<KbAuditLog> {

    List<KbAuditLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<KbAuditLog> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 3.17 反馈关联：trace_id 唯一标识一轮问答，反馈 API 凭此确定性定位审计行回填 feedback */
    Optional<KbAuditLog> findFirstByTraceId(String traceId);

    // Bad Case 运营多选项查询（4.7）经 AuditLogSpecs.search(...) + findAll(spec, pageable)
    // 执行——原 @Query 可选参数形态触发 PG 服务端预编译类型推断缺陷，v2.35 修正
}
