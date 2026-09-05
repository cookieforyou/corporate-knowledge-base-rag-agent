package com.enterprise.kb.domain.spec;

import com.enterprise.kb.domain.model.KbAuditLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志查询 Specification 工厂（Phase 4 簇④ 4.7 定稿，v2.35 形态修正）。
 *
 * <p><b>形态修正动因</b>：原 {@code @Query} 可选参数形态 {@code (:p IS NULL OR ...)}
 * 在 PostgreSQL 服务端预编译提升后报错——PgJDBC 对同一 SQL 文本前若干次执行走无名
 * 语句（绑定期携带类型），达 {@code prepareThreshold}（默认 5）后提升为命名预编译，
 * Parse 期须纯从 SQL 文本推断参数类型，{@code $n IS NULL} 出现位无类型上下文即抛
 * {@code could not determine data type of parameter}（运维中心 500 根因）。
 * Specification 动态谓词只在过滤项非空时拼接，所有绑定出现位恒有类型上下文，
 * 结构性根除该类缺陷。
 *
 * <p>过滤项传 null 不过滤；租户过滤恒在（fail-closed 纪律）；
 * 排序 created_at 倒序（Pageable 无 sort 时保序）。
 */
public final class AuditLogSpecs {

    private AuditLogSpecs() {
    }

    /**
     * Bad Case 运营查询（4.7）：租户域内审计多选项过滤。
     *
     * @param tenantId  租户（必传，恒过滤）
     * @param from      created_at 闭区间下界，null 不限
     * @param to        created_at 闭区间上界，null 不限
     * @param userId    用户过滤，null 不限
     * @param sessionId 会话过滤，null 不限
     * @param mode      链路过滤 rag/tool/agent（小写存储形态，簇⑤ E2E 审计核对项），null 不限
     * @param feedback  POSITIVE/NEGATIVE，null 不限
     * @param status    SUCCESS/REJECTED/ERROR，null 不限
     * @param rootCause 已标注根因，null 不限
     * @param annotated true=已标注（root_cause 非空）/ false=未标注 / null 不限
     */
    public static Specification<KbAuditLog> search(String tenantId,
                                                   LocalDateTime from, LocalDateTime to,
                                                   String userId, String sessionId,
                                                   String mode,
                                                   String feedback, String status,
                                                   String rootCause, Boolean annotated) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (sessionId != null) {
                predicates.add(cb.equal(root.get("sessionId"), sessionId));
            }
            if (mode != null) {
                predicates.add(cb.equal(root.get("mode"), mode));
            }
            if (feedback != null) {
                predicates.add(cb.equal(root.get("feedback"), feedback));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (rootCause != null) {
                predicates.add(cb.equal(root.get("rootCause"), rootCause));
            }
            if (annotated != null) {
                predicates.add(annotated
                    ? cb.isNotNull(root.get("rootCause"))
                    : cb.isNull(root.get("rootCause")));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
