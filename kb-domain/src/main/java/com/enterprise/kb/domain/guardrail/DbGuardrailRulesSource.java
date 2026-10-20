package com.enterprise.kb.domain.guardrail;

import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesSource;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DB 词表源（v2.53 词表 DB 单轨，设计 12.7 词表工程）——
 * {@code rag.guardrail.rules.source=db} 时条件装配，kb_guardrail_rule 表为
 * 唯一事实源（Plan C 修订形态，v2.52 钉死复审推荐）。
 *
 * <p><b>装载语义</b>：编码态 {@code valueB64} 解码为运行时明文
 * （KEYWORD 小写规范化 / REGEX CASE_INSENSITIVE 预编译），与文件源加载层
 * 运行时形态逐字对齐。畸形词项（解码 / 枚举 / 编译失败）<b>fail-fast 上抛</b>——
 * DB 为受控写路径（CRUD 校验在先），损坏不得静默降级；启动期击穿暴露，
 * 热重载期由 Registry fail-keep 承接保旧快照。
 *
 * <p>kb-eval 上下文 source 缺省 file，本源永不装配（测量锁版 + 零 Redis/DB
 * 运营面漂移）；{@link #injectionLocation()} / {@link #outputLocation()} 返回
 * 空串 → mtime 通道不适用（DB 源经 pub/sub、运营端点与修订号轮询触发）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.guardrail.rules.source", havingValue = "db")
public class DbGuardrailRulesSource implements GuardrailRulesSource {

    public static final String SIDE_INJECTION = "injection";
    public static final String SIDE_OUTPUT = "output";

    private final KbGuardrailRuleRepository repository;

    public DbGuardrailRulesSource(KbGuardrailRuleRepository repository) {
        this.repository = repository;
        log.info("护栏词表 DB 源装配（rag.guardrail.rules.source=db，唯一事实源 kb_guardrail_rule）");
    }

    @Override
    public List<GuardrailRule> loadInjectionRules() {
        return load(SIDE_INJECTION);
    }

    @Override
    public List<GuardrailRule> loadOutputRules() {
        return load(SIDE_OUTPUT);
    }

    @Override
    public String sourceName() {
        return "db";
    }

    /**
     * 修订号 = {@code 行数:内容指纹}（12.7 v2.113）：pub/sub 为 fire-and-forget 无 backlog，
     * DB 源无 mtime 可轮询 ⇒ 丢信号即长期陈旧；本方法给协调器一个廉价且**对任何写路径
     * 都敏感**的变更判据（见 {@link KbGuardrailRuleRepository#revision()} 的形态说明：
     * 时间戳型对直改库 SQL 无感，故取内容指纹）。单查询原子快照，239 行表实测亚毫秒。
     */
    @Override
    public String revision() {
        return repository.revision();
    }

    private List<GuardrailRule> load(String side) {
        return repository.findBySideOrderByIdAsc(side).stream()
            .map(DbGuardrailRulesSource::toRule)
            .toList();
    }

    /** 实体 → 运行时词项；畸形 fail-fast（附词项 id 定位）。存档导出复用同口径。 */
    public static GuardrailRule toRule(KbGuardrailRule entity) {
        String id = entity.getId();
        try {
            String decoded = new String(Base64.getDecoder().decode(entity.getValueB64().trim()),
                StandardCharsets.UTF_8);
            RuleType type = RuleType.valueOf(entity.getType().trim().toUpperCase());
            RuleAction action = RuleAction.valueOf(entity.getAction().trim().toUpperCase());
            Pattern compiled = null;
            if (type == RuleType.REGEX) {
                compiled = Pattern.compile(decoded, Pattern.CASE_INSENSITIVE);
            } else {
                decoded = decoded.toLowerCase();
            }
            return new GuardrailRule(id, entity.getFamily(), entity.getLang(), type,
                decoded, action, Boolean.TRUE.equals(entity.getEnabled()), compiled);
        } catch (RuntimeException e) {
            throw new IllegalStateException("护栏词表 DB 词项畸形（id=" + id + "）: " + e.getMessage(), e);
        }
    }
}
