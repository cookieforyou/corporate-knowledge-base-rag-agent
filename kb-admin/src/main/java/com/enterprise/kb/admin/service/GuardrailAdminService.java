package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.DrillResult;
import com.enterprise.kb.admin.dto.GuardrailRulePage;
import com.enterprise.kb.admin.dto.GuardrailRuleView;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.commons.security.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 护栏词表运维服务（安全簇⑥ F2，专项方案 §4.6）——词表列表查询 + 命中演练。
 *
 * <p><b>活视图语义</b>：经 {@link GuardrailRulesRegistry} 读当前快照——F1 热重载
 * 后运营面即时一致（免重启词表运营的验证通道）。
 *
 * <p><b>载荷纪律</b>（第七节）：视图只回词项元数据 + SHA-256 指纹前缀 + 长度，
 * value 明文不出本服务；演练为纯函数调用（{@code TextSanitizer.matchRules}），
 * <b>不计指标不落审计</b>——运营视图不污染度量与审计通道（F2 设计纪律）。
 */
@Service
@RequiredArgsConstructor
public class GuardrailAdminService {

    private static final String SIDE_INJECTION = "injection";
    private static final String SIDE_OUTPUT = "output";
    private static final int FINGERPRINT_LENGTH = 12;
    /** 分页口径与审计日志（AuditLogQueryService）同款：0 基页码、缺省 20、上限 100 */
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final GuardrailRulesRegistry rulesRegistry;

    /**
     * 词表列表分页查询（全部条件可选）：side 缺省双侧；family/lang/action/type
     * 大小写不敏感精确匹配；enabled 布尔过滤。返回元数据视图（value 不回显）。
     *
     * <p>分页施加于活快照过滤结果（内存切片，读路径不触 DB）：page 0 基，
     * null/负数归零；size null/非正归缺省 20，上限 100（同审计日志口径）。
     * 越界页返回空 items（total 不变）——词表规模小，不做页码钳制回弹。
     */
    public GuardrailRulePage listRules(String side, String family, String lang,
                                       String action, Boolean enabled, String type,
                                       Integer page, Integer size) {
        List<GuardrailRuleView> views = new ArrayList<>();
        if (!SIDE_OUTPUT.equalsIgnoreCase(side)) {
            rulesRegistry.currentInjectionRules()
                .forEach(rule -> views.add(toView(rule, SIDE_INJECTION)));
        }
        if (!SIDE_INJECTION.equalsIgnoreCase(side)) {
            rulesRegistry.currentOutputRules()
                .forEach(rule -> views.add(toView(rule, SIDE_OUTPUT)));
        }
        List<GuardrailRuleView> filtered = views.stream()
            .filter(view -> isBlankOrEquals(family, view.family()))
            .filter(view -> isBlankOrEquals(lang, view.lang()))
            .filter(view -> isBlankOrEquals(action, view.action()))
            .filter(view -> enabled == null || view.enabled() == enabled)
            .filter(view -> isBlankOrEquals(type, view.type()))
            .toList();
        int cappedSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageIndex = page == null || page < 0 ? 0 : page;
        int fromIndex = (int) Math.min((long) pageIndex * cappedSize, filtered.size());
        int toIndex = (int) Math.min(fromIndex + (long) cappedSize, filtered.size());
        return new GuardrailRulePage(filtered.subList(fromIndex, toIndex), filtered.size(),
            pageIndex, cappedSize);
    }

    /**
     * 命中演练：输入文本 → 归一化检测视图 → 注入/输出双侧 matchRules
     * （与运行时同口径，仅 enabled 词项参与）。纯运营视图：不计指标不落审计。
     */
    public DrillResult drill(String text) {
        String detectionView = TextSanitizer.normalize(text);
        return new DrillResult(
            matches(detectionView, rulesRegistry.currentInjectionRules(), SIDE_INJECTION),
            matches(detectionView, rulesRegistry.currentOutputRules(), SIDE_OUTPUT));
    }

    private static List<GuardrailRuleView> matches(String detectionView,
                                                   List<GuardrailRule> rules, String side) {
        return TextSanitizer.matchRules(detectionView, rules).stream()
            .map(rule -> toView(rule, side))
            .toList();
    }

    private static boolean isBlankOrEquals(String filter, String value) {
        return filter == null || filter.isBlank() || filter.equalsIgnoreCase(value);
    }

    /** 词项 → 运维视图：指纹（SHA-256 前 12 位）+ 长度替代明文 value */
    private static GuardrailRuleView toView(GuardrailRule rule, String side) {
        return new GuardrailRuleView(rule.id(), side, rule.family(), rule.lang(),
            rule.type().name(), rule.action().name(), rule.enabled(),
            fingerprint(rule.value()), rule.value().length());
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
