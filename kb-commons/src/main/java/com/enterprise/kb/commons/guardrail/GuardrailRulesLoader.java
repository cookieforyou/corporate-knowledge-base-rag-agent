package com.enterprise.kb.commons.guardrail;

import com.enterprise.kb.commons.security.TextSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 护栏词表结构化加载器（安全簇① A1，设计 12.7 词表工程）——对话链路
 * （InputSanitizeAdvisor）与 ETL 入库链路（SanitizingTransformer）的同源消费入口。
 *
 * <p><b>三源合并</b>：内置默认 ∪ 结构化文件 ∪ 既有 CSV 环境变量，按 {@code id} 后者覆盖；
 * 三源统一产出 {@link GuardrailRule} 列表，单一词表口径不变。
 * <ul>
 *   <li><b>内置默认</b>：{@code TextSanitizer.DEFAULT_INJECTION_KEYWORDS}（过渡形态，
 *       安全簇① T2 迁移入结构化文件后收敛为空）；</li>
 *   <li><b>结构化文件</b>：classpath {@code guardrail/injection-rules.yml} /
 *       {@code output-rules.yml}，支持 {@code rag.guardrail.rules.*-location} 外部路径覆盖
 *       （{@code classpath:} / {@code file:} 前缀经 {@link DefaultResourceLoader} 解析）；</li>
 *   <li><b>CSV 兼容源</b>：{@code rag.guardrail.input.injection-keywords}（保留），
 *       语义由「整体替换」演进为「并入合并」——合并后单条自定义词不再抹掉整套内置词表。</li>
 * </ul>
 *
 * <p><b>编码态纪律</b>（第七节条 2）：结构化文件内 {@code value} 为逐条 Base64，
 * 本加载器解码为运行时明文；词表对攻击者无保密意义，编码纯为交付形态约束——
 * 防 AI 辅助链路读取时触发上游注入检测（400 block + 上下文污染）。
 *
 * <p><b>失败语义</b>：资源缺失 → 该源视为空词表（内置默认兜底注入侧基线，warn 记录）；
 * 资源存在但读取/解析失败 → fail-fast 上抛（安全配置损坏不得静默降级）。
 * 单词项畸形（id 缺失除外：Base64 解码失败 / 正则编译失败）→ warn + 跳过该词项。
 */
public final class GuardrailRulesLoader {

    private static final Logger log = LoggerFactory.getLogger(GuardrailRulesLoader.class);

    static final String DEFAULT_INJECTION_LOCATION = "classpath:guardrail/injection-rules.yml";
    static final String DEFAULT_OUTPUT_LOCATION = "classpath:guardrail/output-rules.yml";

    private GuardrailRulesLoader() {
    }

    /** 注入侧词表：内置默认 ∪ CSV 兼容源 ∪ 结构化文件（同 id 结构化文件优先）。 */
    public static List<GuardrailRule> loadInjectionRules(String location, String csvCompat) {
        String resolved = (location == null || location.isBlank()) ? DEFAULT_INJECTION_LOCATION : location;
        List<GuardrailRule> builtin = defaultInjectionRules();
        List<GuardrailRule> legacy = csvRules(csvCompat);
        List<GuardrailRule> structured = loadStructured(resolved);
        return merge(builtin, legacy, structured);
    }

    /** 输出侧词表：CSV 兼容源 ∪ 结构化文件（无内置默认，同 id 结构化文件优先）。 */
    public static List<GuardrailRule> loadOutputRules(String location, String csvCompat) {
        String resolved = (location == null || location.isBlank()) ? DEFAULT_OUTPUT_LOCATION : location;
        List<GuardrailRule> legacy = csvRules(csvCompat);
        List<GuardrailRule> structured = loadStructured(resolved);
        return merge(List.of(), legacy, structured);
    }

    // ── 三源 ──

    private static List<GuardrailRule> defaultInjectionRules() {
        List<String> defaults = TextSanitizer.DEFAULT_INJECTION_KEYWORDS;
        List<GuardrailRule> rules = new ArrayList<>(defaults.size());
        for (int i = 0; i < defaults.size(); i++) {
            rules.add(keywordRule("builtin-inj-" + i, defaults.get(i)));
        }
        return rules;
    }

    private static List<GuardrailRule> csvRules(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<GuardrailRule> rules = new ArrayList<>();
        int index = 0;
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            rules.add(keywordRule("legacy-csv-" + (index++), trimmed));
        }
        return rules;
    }

    private static GuardrailRule keywordRule(String id, String rawValue) {
        return new GuardrailRule(id, GuardrailFamily.UNCLASSIFIED.name(), "",
                RuleType.KEYWORD, rawValue.toLowerCase(), RuleAction.BLOCK, true, null);
    }

    // ── 结构化文件解析 ──

    private static List<GuardrailRule> loadStructured(String location) {
        Resource resource = new DefaultResourceLoader().getResource(location);
        if (!resource.exists()) {
            log.warn("护栏词表资源不存在，该源视为空词表: {}", location);
            return List.of();
        }
        Object root;
        try (InputStream is = resource.getInputStream()) {
            root = new Yaml().load(is);
        } catch (IOException e) {
            throw new IllegalStateException("护栏词表读取失败: " + location, e);
        }
        if (root == null) {
            return List.of();
        }
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("rules") instanceof List<?> rawRules)) {
            return List.of();
        }
        List<GuardrailRule> rules = new ArrayList<>(rawRules.size());
        int skipped = 0;
        for (Object item : rawRules) {
            if (!(item instanceof Map<?, ?> entry)) {
                skipped++;
                continue;
            }
            GuardrailRule rule = parseRule(entry, location);
            if (rule == null) {
                skipped++;
            } else {
                rules.add(rule);
            }
        }
        if (skipped > 0) {
            log.warn("护栏词表 {} 有 {} 条畸形词项被跳过（Base64 解码 / 正则编译 / 结构失败）", location, skipped);
        }
        return rules;
    }

    /** 解析单词项；畸形（解码/编译失败）返回 null 由调用方计数跳过。 */
    private static GuardrailRule parseRule(Map<?, ?> entry, String location) {
        String id = text(entry.get("id"));
        String rawValue = text(entry.get("value"));
        if (id.isBlank() || rawValue.isBlank()) {
            return null;
        }
        RuleType type = parseEnum(RuleType.class, text(entry.get("type")), RuleType.KEYWORD);
        RuleAction action = parseEnum(RuleAction.class, text(entry.get("action")), RuleAction.BLOCK);
        String family = text(entry.get("family"));
        String lang = text(entry.get("lang"));
        boolean enabled = entry.get("enabled") instanceof Boolean b ? b : true;

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(rawValue.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.warn("护栏词项 {} Base64 解码失败，跳过（{}）", id, location);
            return null;
        }

        Pattern compiled = null;
        if (type == RuleType.REGEX) {
            try {
                compiled = Pattern.compile(decoded, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                log.warn("护栏词项 {} 正则编译失败，跳过（{}）", id, location);
                return null;
            }
        } else {
            decoded = decoded.toLowerCase();
        }
        return new GuardrailRule(id, family.isBlank() ? GuardrailFamily.UNCLASSIFIED.name() : family,
                lang, type, decoded, action, enabled, compiled);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String raw, E fallback) {
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("护栏词表枚举值非法 {}，回落 {}", raw, fallback);
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    // ── 合并 ──

    /**
     * 三源按 id 合并（LinkedHashMap 保序，同 id 后者覆盖前者）。
     * 覆盖优先级自低至高：内置默认 → CSV 兼容源 → 结构化文件（外部文件优先，设计定案）。
     */
    private static List<GuardrailRule> merge(List<GuardrailRule> builtin,
                                             List<GuardrailRule> legacy,
                                             List<GuardrailRule> structured) {
        LinkedHashMap<String, GuardrailRule> byId = new LinkedHashMap<>();
        for (GuardrailRule rule : builtin) {
            byId.put(rule.id(), rule);
        }
        for (GuardrailRule rule : legacy) {
            byId.put(rule.id(), rule);
        }
        for (GuardrailRule rule : structured) {
            byId.put(rule.id(), rule);
        }
        return List.copyOf(byId.values());
    }
}
