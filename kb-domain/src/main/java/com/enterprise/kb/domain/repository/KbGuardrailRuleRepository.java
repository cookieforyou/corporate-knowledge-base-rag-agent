package com.enterprise.kb.domain.repository;

import com.enterprise.kb.domain.model.KbGuardrailRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
     * 词表最新修改时间（v2.113 丢信号兜底轮询的一半）：与内建 {@code count()} 组合成
     * 修订号——count 捕增删、max 捕修改（{@code @PreUpdate} 恒刷新时间戳）。
     * 单列聚合（标量返回）是 Spring Data JPA 最保守形态：本机无 Docker 无法在
     * 构建期跑 JPA 上下文，一律避开启动期才校验的多列 {@code Object[]} 聚合。
     */
    @Query("select max(r.updatedAt) from KbGuardrailRule r")
    LocalDateTime maxUpdatedAt();
}
