package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbGuardrailRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 护栏词项仓储（v2.53 词表 DB 单轨，设计 12.7 词表工程）。
 */
@Repository
public interface KbGuardrailRuleRepository extends JpaRepository<KbGuardrailRule, String> {

    /** 按侧别装载全量（id 升序保快照稳定）——DbGuardrailRulesSource 消费 */
    List<KbGuardrailRule> findBySideOrderByIdAsc(String side);

    /** 去重键查询：(side, type, fingerprint) 唯一约束的预检通道 */
    Optional<KbGuardrailRule> findBySideAndTypeAndFingerprint(String side, String type, String fingerprint);

    /**
     * 词表修订号（v2.113 丢信号兜底轮询）：{@code 行数:内容指纹} 单查询原子快照——
     * 指纹覆盖全部语义列（id/side/family/lang/type/action/enabled/value_b64/fingerprint），
     * <b>不含审计列</b>（origin/created_by/updated_by/时间戳：纯审计触碰不改运行时行为，
     * 不应触发装载）。
     *
     * <p><b>为何用内容指纹而非 {@code max(updated_at)}</b>（本地 E2E 实证修正）：
     * 直接改库的运维 SQL（如 G4 口径回滚 {@code UPDATE ... SET action='FLAG' WHERE id IN (...)}）
     * <b>不会触碰 {@code updated_at}</b>（{@code @PreUpdate} 只在 Hibernate 写路径生效）
     * ⇒ 时间戳型修订号对这条真实路径完全无感、兜底失效。指纹型对任何写路径都敏感。
     *
     * <p>原生 SQL 说明：PG 侧 {@code string_agg + md5}（本仓 PG 单栈）；词表量级为数百行，
     * 单查询开销亚毫秒；返回的是哈希串，词值明文不出库。
     */
    @Query(value = "SELECT count(*) || ':' || coalesce(md5(string_agg("
        + "id || '|' || side || '|' || family || '|' || lang || '|' || type || '|' || action || '|' "
        + "|| enabled::text || '|' || value_b64 || '|' || fingerprint, ',' ORDER BY id)), '-') "
        + "FROM kb_guardrail_rule", nativeQuery = true)
    String revision();
}
