package com.enterprise.kb.admin.service;

import com.enterprise.kb.commons.guardrail.FileGuardrailRulesSource;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesSupport;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 护栏词表存量迁移器（v2.53 词表 DB 单轨，设计 12.7 词表工程）——
 * {@code rag.guardrail.rules.source=db} 首启时将文件源有效全集
 * （外部覆盖 ∪ CSV 兼容源合并视图）一次性灌入 kb_guardrail_rule，
 * 完成 Git Ops → DB 单轨的事实源切换；幂等（非空表跳过）。
 *
 * <p><b>关键装配纪律</b>：source=db 时上下文内 active 的
 * {@code GuardrailRulesSource} Bean 是 DB 源（表空）——本迁移器<b>不得注入
 * SPI</b>，须以配置手工构造 {@link FileGuardrailRulesSource} 读文件源合并视图，
 * 否则空表自举迁移为零。
 *
 * <p>kb-eval 双隔离：source 缺省 file（本器条件不成立）+ web-none 结构隔离。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.guardrail.rules.source", havingValue = "db")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GuardrailRulesSeeder implements ApplicationRunner {

    private static final String SIDE_INJECTION = "injection";
    private static final String SIDE_OUTPUT = "output";
    private static final String ORIGIN_MIGRATION = "MIGRATION";
    private static final String OPERATOR = "migration-seeder";

    private final KbGuardrailRuleRepository repository;
    private final FileGuardrailRulesSource fileSource;
    private final String injectionCsv;
    private final String outputCsv;

    public GuardrailRulesSeeder(KbGuardrailRuleRepository repository,
                                @Value("${rag.guardrail.rules.injection-location:}") String injectionLocation,
                                @Value("${rag.guardrail.input.injection-keywords:}") String injectionCsv,
                                @Value("${rag.guardrail.rules.output-location:}") String outputLocation,
                                @Value("${rag.guardrail.output.blacklist:}") String outputCsv) {
        this.repository = repository;
        this.fileSource = new FileGuardrailRulesSource(injectionLocation, injectionCsv, outputLocation, outputCsv);
        this.injectionCsv = injectionCsv;
        this.outputCsv = outputCsv;
    }

    @Override
    public void run(ApplicationArguments args) {
        long existing = repository.count();
        if (existing > 0) {
            log.info("护栏词表迁移跳过：kb_guardrail_rule 非空（{} 条），DB 单轨事实源已就位", existing);
            return;
        }
        if (injectionCsv != null && !injectionCsv.isBlank() || outputCsv != null && !outputCsv.isBlank()) {
            log.warn("CSV 兼容源配置非空——DB 单轨下其存量经本次迁移并库后退役，建议迁移核验后清空配置");
        }
        List<KbGuardrailRule> entities = new ArrayList<>();
        Set<String> dedupKeys = new HashSet<>();
        int dupSkipped = 0;
        dupSkipped += collect(fileSource.loadInjectionRules(), SIDE_INJECTION, entities, dedupKeys);
        dupSkipped += collect(fileSource.loadOutputRules(), SIDE_OUTPUT, entities, dedupKeys);
        repository.saveAll(entities);
        log.info("护栏词表存量迁移完成（Git Ops → DB 单轨）：灌入 {} 条（注入侧+输出侧），origin={}{}",
            entities.size(), ORIGIN_MIGRATION, dupSkipped > 0 ? "，指纹重复跳过 " + dupSkipped + " 条" : "");
    }

    /** 词表快照 → 实体；(side, type, fingerprint) 内存去重兜底唯一约束。 */
    private static int collect(List<GuardrailRule> rules, String side,
                               List<KbGuardrailRule> sink, Set<String> dedupKeys) {
        int skipped = 0;
        for (GuardrailRule rule : rules) {
            KbGuardrailRule entity = toEntity(rule, side);
            if (!dedupKeys.add(entity.getSide() + "|" + entity.getType() + "|" + entity.getFingerprint())) {
                skipped++;
                continue;
            }
            sink.add(entity);
        }
        return skipped;
    }

    private static KbGuardrailRule toEntity(GuardrailRule rule, String side) {
        KbGuardrailRule entity = new KbGuardrailRule();
        entity.setId(rule.id());
        entity.setSide(side);
        entity.setFamily(rule.family());
        entity.setLang(rule.lang());
        entity.setType(rule.type().name());
        // 运行时 value（KEYWORD 已小写化）重编码落库——与 CRUD 写路径规范化语义一致
        entity.setValueB64(GuardrailRulesSupport.encodeB64(rule.value()));
        entity.setFingerprint(GuardrailRulesSupport.sha256Hex(rule.value()));
        entity.setAction(rule.action().name());
        entity.setEnabled(rule.enabled());
        entity.setOrigin(ORIGIN_MIGRATION);
        entity.setCreatedBy(OPERATOR);
        entity.setUpdatedBy(OPERATOR);
        return entity;
    }
}
