package com.enterprise.kb.domain.spec;

import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户反馈查询 Specification 工厂（3.17 Bad Case 查询，v2.35 形态修正）。
 *
 * <p>形态修正动因同 {@link AuditLogSpecs}：原 {@code @Query} 的
 * {@code (:rating IS NULL OR ...)} 可选参数在 PostgreSQL 服务端预编译提升后
 * 触发 {@code could not determine data type of parameter}——该缺陷为潜伏态
 *（PgJDBC 无名语句阶段绑定期携类型不报错，命名预编译 Parse 期才暴露），
 * 与审计查询同源修正为 Specification 动态谓词。
 *
 * <p>租户收敛语义不变（3.17 fail-closed）：kb_feedback 无租户列，经
 * message→session 两级子查询收敛到租户可见域；原 {@code CAST(f.rating AS String)}
 * 字符串比较随动态谓词废除，rating 直接枚举比较。
 */
public final class FeedbackSpecs {

    private FeedbackSpecs() {
    }

    /**
     * 租户可见域内反馈查询（rating/resolved 传 null 不过滤），created_at 倒序。
     */
    public static Specification<KbFeedback> tenantFeedback(String tenantId,
                                                           FeedbackRating rating,
                                                           Boolean resolved) {
        return (root, query, cb) -> {
            Subquery<String> sessions = query.subquery(String.class);
            Root<KbSession> session = sessions.from(KbSession.class);
            sessions.select(session.get("id")).where(cb.equal(session.get("tenantId"), tenantId));

            Subquery<String> messages = query.subquery(String.class);
            Root<KbMessage> message = messages.from(KbMessage.class);
            messages.select(message.get("id")).where(message.get("sessionId").in(sessions));

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("messageId").in(messages));
            if (rating != null) {
                predicates.add(cb.equal(root.get("rating"), rating));
            }
            if (resolved != null) {
                predicates.add(cb.equal(root.get("resolved"), resolved));
            }
            query.orderBy(cb.desc(root.get("createdAt")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
