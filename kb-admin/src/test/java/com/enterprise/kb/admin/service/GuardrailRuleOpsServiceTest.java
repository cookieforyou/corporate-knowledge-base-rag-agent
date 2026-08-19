package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GuardrailRuleCreateRequest;
import com.enterprise.kb.admin.dto.GuardrailRuleMutationResult;
import com.enterprise.kb.admin.dto.GuardrailRuleUpdateRequest;
import com.enterprise.kb.admin.dto.ReloadResult;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.commons.guardrail.GuardrailRulesSupport;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 词表运营服务测试（v2.53 DB 单轨 CRUD）：校验链 / 错误码 / 写后闭环
 * （reload + 广播 + 存档 + 指标）/ id 生成与冲突重试。
 * 纯 JUnit + Mockito，占位词表零字面攻击词。
 */
class GuardrailRuleOpsServiceTest {

    private KbGuardrailRuleRepository repository;
    private GuardrailRulesRegistry registry;
    private RedissonClient redissonClient;
    private AiBusinessMetrics metrics;
    private GuardrailRuleOpsService service;

    @TempDir
    Path exportDir;

    @BeforeEach
    void setUp() {
        repository = mock(KbGuardrailRuleRepository.class);
        registry = new GuardrailRulesRegistry(
            "classpath:guardrail-test/admin-injection-rules.yml", "",
            "classpath:guardrail-test/admin-output-rules.yml", "");
        redissonClient = mock(RedissonClient.class);
        metrics = mock(AiBusinessMetrics.class);
        when(redissonClient.getTopic(anyString(), any(Codec.class))).thenReturn(mock(RTopic.class));
        when(repository.findAll()).thenReturn(List.of());
        when(repository.findBySideAndTypeAndFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(repository.findBySideOrderByIdAsc(anyString())).thenReturn(List.of());
        when(repository.save(any(KbGuardrailRule.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        service = new GuardrailRuleOpsService(repository, registry, redissonClient, metrics,
            exportDir.toString());
    }

    private static GuardrailRuleCreateRequest create(String side, String family, String decoded) {
        return new GuardrailRuleCreateRequest(side, family,
            GuardrailRulesSupport.encodeB64(decoded), null, null, null, null);
    }

    @Test
    void createAppliesDefaultsNormalizesAndClosesLoop() {
        GuardrailRuleMutationResult result = service.create(
            create("injection", "UNCLASSIFIED", "Probe-Word-A"), "t-1");

        ArgumentCaptor<KbGuardrailRule> captor = ArgumentCaptor.forClass(KbGuardrailRule.class);
        verify(repository).save(captor.capture());
        KbGuardrailRule saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo("api-inj-1");
        assertThat(saved.getSide()).isEqualTo("injection");
        assertThat(saved.getType()).isEqualTo("KEYWORD");
        assertThat(saved.getAction()).isEqualTo("FLAG");
        assertThat(saved.getEnabled()).isTrue();
        assertThat(saved.getOrigin()).isEqualTo("API");
        assertThat(saved.getCreatedBy()).isEqualTo("t-1");
        // KEYWORD 小写规范化后重编码 + 指纹同口径
        assertThat(saved.getValueB64()).isEqualTo(GuardrailRulesSupport.encodeB64("probe-word-a"));
        assertThat(saved.getFingerprint()).isEqualTo(GuardrailRulesSupport.sha256Hex("probe-word-a"));
        assertThat(result.reloaded()).isTrue();
        assertThat(result.rule().id()).isEqualTo("api-inj-1");
        verify(metrics).recordGuardrailOps("create");
        verify(redissonClient).getTopic(anyString(), any(Codec.class));
    }

    @Test
    void createAllowsExplicitBlock() {
        GuardrailRuleCreateRequest req = new GuardrailRuleCreateRequest("output",
            "BUSINESS_CONFIDENTIAL", GuardrailRulesSupport.encodeB64("probe-out"),
            "zh", "KEYWORD", "BLOCK", true);

        service.create(req, "t-1");

        ArgumentCaptor<KbGuardrailRule> captor = ArgumentCaptor.forClass(KbGuardrailRule.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("BLOCK");
        assertThat(captor.getValue().getId()).isEqualTo("api-out-1");
    }

    @Test
    void createRejectsInvalidBase64() {
        GuardrailRuleCreateRequest req = new GuardrailRuleCreateRequest("injection",
            "UNCLASSIFIED", "!!!not-base64!!!", null, null, null, null);

        assertThatThrownBy(() -> service.create(req, "t-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GUARDRAIL_RULE_INVALID");
    }

    @Test
    void createRejectsFamilyMismatchForSide() {
        assertThatThrownBy(() -> service.create(create("output", "JAILBREAK", "probe"), "t-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GUARDRAIL_RULE_INVALID");
    }

    @Test
    void createRejectsInvalidRegexPattern() {
        GuardrailRuleCreateRequest req = new GuardrailRuleCreateRequest("injection",
            "UNCLASSIFIED", GuardrailRulesSupport.encodeB64("([unclosed"),
            null, "REGEX", null, null);

        assertThatThrownBy(() -> service.create(req, "t-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GUARDRAIL_RULE_INVALID");
    }

    @Test
    void createRejectsDuplicateFingerprint() {
        KbGuardrailRule existing = new KbGuardrailRule();
        existing.setId("import-inj-1");
        when(repository.findBySideAndTypeAndFingerprint(anyString(), anyString(), anyString()))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(create("injection", "UNCLASSIFIED", "probe"), "t-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GUARDRAIL_RULE_DUPLICATE");
    }

    @Test
    void getThrowsWhenMissing() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("GUARDRAIL_RULE_NOT_FOUND");
    }

    @Test
    void updateTogglesEnabledWithoutTouchingValue() {
        KbGuardrailRule entity = new KbGuardrailRule();
        entity.setId("api-inj-1");
        entity.setSide("injection");
        entity.setFamily("UNCLASSIFIED");
        entity.setType("KEYWORD");
        entity.setValueB64(GuardrailRulesSupport.encodeB64("probe-word-a"));
        entity.setFingerprint(GuardrailRulesSupport.sha256Hex("probe-word-a"));
        entity.setAction("FLAG");
        entity.setEnabled(true);
        when(repository.findById("api-inj-1")).thenReturn(Optional.of(entity));

        GuardrailRuleMutationResult result = service.update("api-inj-1",
            new GuardrailRuleUpdateRequest(null, null, null, null, null, false), "t-1");

        assertThat(entity.getEnabled()).isFalse();
        assertThat(entity.getUpdatedBy()).isEqualTo("t-1");
        assertThat(entity.getValueB64()).isEqualTo(GuardrailRulesSupport.encodeB64("probe-word-a"));
        assertThat(result.reloaded()).isTrue();
        verify(metrics).recordGuardrailOps("update");
    }

    @Test
    void deleteRemovesAndRecordsMetric() {
        KbGuardrailRule entity = new KbGuardrailRule();
        entity.setId("api-inj-1");
        entity.setSide("injection");
        entity.setFamily("UNCLASSIFIED");
        entity.setType("KEYWORD");
        entity.setValueB64(GuardrailRulesSupport.encodeB64("probe-word-a"));
        entity.setFingerprint(GuardrailRulesSupport.sha256Hex("probe-word-a"));
        entity.setAction("FLAG");
        entity.setEnabled(true);
        when(repository.findById("api-inj-1")).thenReturn(Optional.of(entity));

        GuardrailRuleMutationResult result = service.delete("api-inj-1");

        verify(repository).delete(entity);
        verify(metrics).recordGuardrailOps("delete");
        assertThat(result.rule().id()).isEqualTo("api-inj-1");
    }

    @Test
    void reloadReturnsSourceAndSnapshotCounts() {
        ReloadResult result = service.reload();

        assertThat(result.source()).isEqualTo("file");
        assertThat(result.reloaded()).isTrue();
        // 占位词表：注入侧 3 条 / 输出侧 1 条
        assertThat(result.injectionCount()).isEqualTo(3);
        assertThat(result.outputCount()).isEqualTo(1);
    }

    @Test
    void createExportsEncodedArchiveFiles() throws Exception {
        service.create(create("injection", "UNCLASSIFIED", "probe-archive"), "t-1");

        Path injectionArchive = exportDir.resolve("injection-rules.yml");
        Path outputArchive = exportDir.resolve("output-rules.yml");
        assertThat(injectionArchive).exists();
        assertThat(outputArchive).exists();
        assertThat(Files.readString(injectionArchive)).contains("rules:");
    }

    @Test
    void createRegeneratesIdOnPrimaryKeyConflict() {
        KbGuardrailRule taken = new KbGuardrailRule();
        taken.setId("api-inj-1");
        when(repository.findAll()).thenReturn(List.of(taken));
        when(repository.save(any(KbGuardrailRule.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("pk"))
            .thenAnswer(inv -> inv.getArgument(0));

        service.create(create("injection", "UNCLASSIFIED", "probe-retry"), "t-1");

        ArgumentCaptor<KbGuardrailRule> captor = ArgumentCaptor.forClass(KbGuardrailRule.class);
        verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("api-inj-2");
    }
}
