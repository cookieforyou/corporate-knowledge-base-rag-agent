package com.enterprise.kb.admin.service;

import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 词表存量迁移测试（v2.53 DB 单轨）：空表 → 文件源有效全集灌入
 * （origin=MIGRATION，指纹去重兜底）+ 灌库后主动热重载（v2.53.1 启动时序修正）；
 * 非空表 → 幂等跳过。占位词表：guardrail-test/admin-*-rules.yml（零字面攻击词）。
 */
class GuardrailRulesSeederTest {

    private static final String INJECTION_LOC = "classpath:guardrail-test/admin-injection-rules.yml";
    private static final String OUTPUT_LOC = "classpath:guardrail-test/admin-output-rules.yml";

    private KbGuardrailRuleRepository repository;
    private GuardrailRulesRegistry registry;
    private RedissonClient redissonClient;
    private GuardrailRulesSeeder seeder;

    @BeforeEach
    void setUp() {
        repository = mock(KbGuardrailRuleRepository.class);
        registry = mock(GuardrailRulesRegistry.class);
        redissonClient = mock(RedissonClient.class);
        when(registry.reload()).thenReturn(true);
        when(registry.currentInjectionRules()).thenReturn(List.of());
        when(registry.currentOutputRules()).thenReturn(List.of());
        when(redissonClient.getTopic(anyString(), any(Codec.class))).thenReturn(mock(RTopic.class));
        seeder = new GuardrailRulesSeeder(repository, registry, redissonClient,
            INJECTION_LOC, "", OUTPUT_LOC, "");
    }

    @Test
    void seedsFileSourceViewOnEmptyTable() {
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KbGuardrailRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        List<KbGuardrailRule> seeded = captor.getValue();
        // 占位词表：注入侧 3 条（其中 2 条 KEYWORD 解码值相同 → 指纹去重跳过 1）+ 输出侧 1 条
        assertThat(seeded).hasSize(3);
        assertThat(seeded).allSatisfy(entity -> {
            assertThat(entity.getOrigin()).isEqualTo("MIGRATION");
            assertThat(entity.getValueB64()).isNotBlank();
            assertThat(entity.getFingerprint()).hasSize(64);
            assertThat(entity.getCreatedBy()).isEqualTo("migration-seeder");
        });
        assertThat(seeded).extracting(KbGuardrailRule::getId)
            .containsExactlyInAnyOrder("admin-probe-inj-kw", "admin-probe-inj-regex", "admin-probe-out-kw");
        assertThat(seeded).extracting(KbGuardrailRule::getSide)
            .containsExactlyInAnyOrder("injection", "injection", "output");
    }

    /** v2.53.1：Registry 构造早于 Runner（首启空表装载 0 条），灌库后必须主动重载 */
    @Test
    void reloadsRegistryAndBroadcastsAfterSeeding() {
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        verify(registry).reload();
        verify(redissonClient).getTopic(anyString(), any(Codec.class));
    }

    @Test
    void skipsWhenTableNotEmpty() {
        when(repository.count()).thenReturn(5L);

        seeder.run(null);

        verify(repository, never()).saveAll(anyList());
        verify(registry, never()).reload();
    }
}
