package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbGuardrailRule;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
