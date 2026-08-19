package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GuardrailRuleCreateRequest;
import com.enterprise.kb.admin.dto.GuardrailRuleEditView;
import com.enterprise.kb.admin.dto.GuardrailRuleMutationResult;
import com.enterprise.kb.admin.dto.GuardrailRuleUpdateRequest;
import com.enterprise.kb.admin.dto.ReloadResult;
import com.enterprise.kb.ai.guardrail.GuardrailReloadCoordinator;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailFamily;
import com.enterprise.kb.commons.guardrail.GuardrailRulesExporter;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.commons.guardrail.GuardrailRulesSupport;
import com.enterprise.kb.commons.guardrail.OutputFamily;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.domain.guardrail.DbGuardrailRulesSource;
import com.enterprise.kb.domain.model.KbGuardrailRule;
import com.enterprise.kb.domain.repository.KbGuardrailRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 护栏词表运营服务（v2.53 词表 DB 单轨，设计 12.7 词表工程 / Plan C 修订形态）
 * ——CRUD 写路径 + 热更新触发。与只读 {@link GuardrailAdminService}（F2 列表/演练，
 * 指纹契约）分治：本服务直写 kb_guardrail_rule（唯一事实源），写后
 * 本地 {@code registry.reload()} 同步生效 + pub/sub 集群广播 + 编码 YAML
 * 存档导出（git 归档 / eval 引用 / 回滚指回）。
 *
 * <p><b>载荷纪律</b>（第七节）：API 契约只收 valueB64 编码态（前端编码后上送），
 * 服务端解码仅用于校验/规范化，日志与响应不承载明文——列表视图仍走
 * {@link GuardrailRuleEditView} 之外的 F2 指纹契约，value 明文不出编辑预填通道。
 *
 * <p><b>A4 生命周期</b>：新建缺省 action=FLAG（观察档），晋升 BLOCK 须显式
 * 指定（前端强警示 + 干净集零误伤纪律带外把关）。
 *
 * <p><b>fail-keep 语义</b>：写后本地热重载失败（词表装载异常）不阻断写操作
 * 受理——DB 已提交为事实，响应 {@code reloaded=false} 提示经手动重载重试。
 */
@Slf4j
@Service
public class GuardrailRuleOpsService {

    private static final String SIDE_INJECTION = "injection";
    private static final String SIDE_OUTPUT = "output";
    private static final int MAX_VALUE_CHARS = 500;
    private static final String ORIGIN_API = "API";
    private static final String API_INJ_PREFIX = "api-inj-";
    private static final String API_OUT_PREFIX = "api-out-";

    private final KbGuardrailRuleRepository repository;
    private final GuardrailRulesRegistry registry;
    private final RedissonClient redissonClient;
    private final AiBusinessMetrics metrics;
    private final String exportDir;

    public GuardrailRuleOpsService(KbGuardrailRuleRepository repository,
                                   GuardrailRulesRegistry registry,
                                   RedissonClient redissonClient,
                                   AiBusinessMetrics metrics,
                                   @Value("${rag.guardrail.rules.export-dir:}") String exportDir) {
        this.repository = repository;
        this.registry = registry;
        this.redissonClient = redissonClient;
        this.metrics = metrics;
        this.exportDir = exportDir;
    }

    // ── CRUD ──

    /** 新建词项：校验 → 生成 id → 写库 → 生效闭环（reload + 广播 + 存档 + 指标）。 */
    public GuardrailRuleMutationResult create(GuardrailRuleCreateRequest req, String operator) {
        String side = normalizeSide(req.side());
        RuleType type = parseType(req.type());
        RuleAction action = parseAction(req.action());
        String family = validateFamily(side, req.family());
        NormalizedValue nv = normalizeValue(type, req.valueB64());
        checkDuplicate(side, type, nv.fingerprint(), null);

        KbGuardrailRule entity = new KbGuardrailRule();
        entity.setId(nextApiId(side));
        entity.setSide(side);
        entity.setFamily(family);
        entity.setLang(req.lang() == null ? "" : req.lang().trim());
        entity.setType(type.name());
        entity.setValueB64(nv.encoded());
        entity.setFingerprint(nv.fingerprint());
        entity.setAction(action.name());
        entity.setEnabled(req.enabled() == null || req.enabled());
        entity.setOrigin(ORIGIN_API);
        entity.setCreatedBy(operator);
        entity.setUpdatedBy(operator);

        KbGuardrailRule saved = saveWithRetry(entity, true);
        boolean reloaded = postWrite("create");
        return new GuardrailRuleMutationResult(toEditView(saved), reloaded);
    }

    /** 单词项详情（编辑预填视图，含 valueB64 编码态）。 */
    public GuardrailRuleEditView get(String id) {
        return toEditView(findOrThrow(id));
    }

    /** 更新词项：null 字段保持原值；valueB64/type 变化重算指纹与去重。 */
    public GuardrailRuleMutationResult update(String id, GuardrailRuleUpdateRequest req, String operator) {
        KbGuardrailRule entity = findOrThrow(id);
        RuleType type = req.type() != null ? parseType(req.type()) : RuleType.valueOf(entity.getType());
        RuleAction action = req.action() != null ? parseAction(req.action()) : RuleAction.valueOf(entity.getAction());
        String family = req.family() != null ? validateFamily(entity.getSide(), req.family()) : entity.getFamily();

        if (req.valueB64() != null) {
            NormalizedValue nv = normalizeValue(type, req.valueB64());
            checkDuplicate(entity.getSide(), type, nv.fingerprint(), id);
            entity.setValueB64(nv.encoded());
            entity.setFingerprint(nv.fingerprint());
        } else if (type != RuleType.valueOf(entity.getType())) {
            // 类型变更但 value 保持——以新类型复核存量值（REGEX 预编译）
            validateStoredValue(type, entity.getValueB64(), id);
        }
        if (req.lang() != null) {
            entity.setLang(req.lang().trim());
        }
        entity.setType(type.name());
        entity.setAction(action.name());
        entity.setFamily(family);
        if (req.enabled() != null) {
            entity.setEnabled(req.enabled());
        }
        entity.setUpdatedBy(operator);

        KbGuardrailRule saved = saveWithRetry(entity, false);
        boolean reloaded = postWrite("update");
        return new GuardrailRuleMutationResult(toEditView(saved), reloaded);
    }

    /** 删除词项（物理删；git 存档留有历史，运营面主推停用）。 */
    public GuardrailRuleMutationResult delete(String id) {
        KbGuardrailRule entity = findOrThrow(id);
        repository.delete(entity);
        boolean reloaded = postWrite("delete");
        return new GuardrailRuleMutationResult(toEditView(entity), reloaded);
    }

    // ── 热更新触发 ──

    /**
     * 手动热更新：本地 {@code registry.reload()} 同步执行 + pub/sub 广播
     * （发布实例自身订阅会再触发一次 synchronized 幂等重载，无害；
     * 协调器按返回值落 rag.guardrail.reload.succeeded/failed 账）。
     */
    public ReloadResult reload() {
        boolean reloaded = registry.reload();
        publishSignal("manual");
        return new ReloadResult(registry.sourceName(), reloaded,
            registry.currentInjectionRules().size(), registry.currentOutputRules().size());
    }

    // ── 写后闭环 ──

    private boolean postWrite(String operation) {
        boolean reloaded = registry.reload();
        if (!reloaded) {
            log.warn("词表写操作已受理（op={}）但本地热重载 fail-keep 保旧快照——经手动重载端点重试", operation);
        }
        publishSignal(operation);
        exportArchive();
        metrics.recordGuardrailOps(operation);
        return reloaded;
    }

    private void publishSignal(String reason) {
        try {
            redissonClient.getTopic(GuardrailReloadCoordinator.RELOAD_CHANNEL, StringCodec.INSTANCE)
                .publish("crud-" + reason);
        } catch (RuntimeException e) {
            log.warn("词表重载信号发布失败（本地已生效，集群同步降级）: {}", e.getMessage());
        }
    }

    /** 存档导出（best-effort）：DB 是唯一事实源，导出失败仅降级日志不阻断运营。 */
    private void exportArchive() {
        if (exportDir == null || exportDir.isBlank()) {
            return;
        }
        try {
            Path dir = Path.of(exportDir);
            Files.createDirectories(dir);
            writeYaml(dir.resolve("injection-rules.yml"), SIDE_INJECTION);
            writeYaml(dir.resolve("output-rules.yml"), SIDE_OUTPUT);
        } catch (Exception e) {
            log.warn("词表存档导出失败（DB 事实源不受影响，git 快照滞后需人工复核）: {}", e.getMessage());
        }
    }

    private void writeYaml(Path target, String side) throws IOException {
        String yaml = GuardrailRulesExporter.toYaml(
            repository.findBySideOrderByIdAsc(side).stream()
                .map(DbGuardrailRulesSource::toRule)
                .toList(),
            side);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── 校验链 ──

    private record NormalizedValue(String encoded, String fingerprint) {
    }

    private static NormalizedValue normalizeValue(RuleType type, String valueB64) {
        String decoded;
        try {
            decoded = GuardrailRulesSupport.decodeB64(valueB64);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("valueB64 非合法 Base64 编码");
        }
        if (decoded.isBlank()) {
            throw invalid("词值解码后为空");
        }
        if (decoded.length() > MAX_VALUE_CHARS) {
            throw invalid("词值解码后超过 " + MAX_VALUE_CHARS + " 字符上限");
        }
        if (type == RuleType.REGEX) {
            try {
                Pattern.compile(decoded, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw invalid("REGEX 模式预编译失败: " + e.getMessage());
            }
        } else {
            decoded = decoded.toLowerCase();
        }
        return new NormalizedValue(GuardrailRulesSupport.encodeB64(decoded),
            GuardrailRulesSupport.sha256Hex(decoded));
    }

    private static void validateStoredValue(RuleType type, String valueB64, String id) {
        if (type != RuleType.REGEX) {
            return;
        }
        try {
            Pattern.compile(GuardrailRulesSupport.decodeB64(valueB64), Pattern.CASE_INSENSITIVE);
        } catch (RuntimeException e) {
            throw invalid("存量词值不兼容 REGEX 类型（id=" + id + "），须随类型变更一并更新 valueB64");
        }
    }

    private static String normalizeSide(String raw) {
        String side = raw == null ? "" : raw.trim();
        if (SIDE_INJECTION.equalsIgnoreCase(side)) {
            return SIDE_INJECTION;
        }
        if (SIDE_OUTPUT.equalsIgnoreCase(side)) {
            return SIDE_OUTPUT;
        }
        throw invalid("侧别非法（injection | output）");
    }

    private static RuleType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return RuleType.KEYWORD;
        }
        try {
            return RuleType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalid("匹配类型非法（KEYWORD | REGEX）");
        }
    }

    private static RuleAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return RuleAction.FLAG;
        }
        try {
            return RuleAction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw invalid("动作档非法（BLOCK | FLAG）");
        }
    }

    private static String validateFamily(String side, String familyRaw) {
        String family = familyRaw == null ? "" : familyRaw.trim().toUpperCase();
        if (family.isEmpty()) {
            throw invalid("族系不可为空");
        }
        try {
            return SIDE_INJECTION.equals(side)
                ? GuardrailFamily.valueOf(family).name()
                : OutputFamily.valueOf(family).name();
        } catch (IllegalArgumentException e) {
            throw invalid("族系非法（" + side + " 侧枚举不匹配）: " + family);
        }
    }

    private void checkDuplicate(String side, RuleType type, String fingerprint, String excludeId) {
        repository.findBySideAndTypeAndFingerprint(side, type.name(), fingerprint)
            .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
            .ifPresent(existing -> {
                throw new BusinessException("GUARDRAIL_RULE_DUPLICATE",
                    "同值词项已存在（去重指纹命中: " + existing.getId() + "）");
            });
    }

    private KbGuardrailRule findOrThrow(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new BusinessException("GUARDRAIL_RULE_NOT_FOUND", "词项不存在: " + id));
    }

    /** id 自动生成（api-inj-/api-out- 前缀 + 同前缀最大序号+1），PK 冲突兜底重试一次。 */
    private KbGuardrailRule saveWithRetry(KbGuardrailRule entity, boolean regenerateIdOnConflict) {
        try {
            return repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            if (regenerateIdOnConflict) {
                entity.setId(nextApiId(entity.getSide()));
                try {
                    return repository.save(entity);
                } catch (DataIntegrityViolationException retry) {
                    throw duplicate();
                }
            }
            throw duplicate();
        }
    }

    private String nextApiId(String side) {
        String prefix = SIDE_INJECTION.equals(side) ? API_INJ_PREFIX : API_OUT_PREFIX;
        int max = 0;
        for (KbGuardrailRule rule : repository.findAll()) {
            if (rule.getId().startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(rule.getId().substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // 非数字后缀忽略
                }
            }
        }
        return prefix + (max + 1);
    }

    private static GuardrailRuleEditView toEditView(KbGuardrailRule entity) {
        String decoded = GuardrailRulesSupport.decodeB64(entity.getValueB64());
        String fingerprint = entity.getFingerprint();
        return new GuardrailRuleEditView(entity.getId(), entity.getSide(), entity.getFamily(),
            entity.getLang(), entity.getType(), entity.getAction(),
            Boolean.TRUE.equals(entity.getEnabled()), entity.getValueB64(),
            fingerprint.substring(0, Math.min(12, fingerprint.length())), decoded.length(),
            entity.getOrigin(), entity.getCreatedBy(), entity.getUpdatedBy(),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("GUARDRAIL_RULE_INVALID", message);
    }

    private static BusinessException duplicate() {
        return new BusinessException("GUARDRAIL_RULE_DUPLICATE", "词项唯一约束冲突（并发写入或同值重复）");
    }
}
