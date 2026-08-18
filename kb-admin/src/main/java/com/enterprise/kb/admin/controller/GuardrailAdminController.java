package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.DrillRequest;
import com.enterprise.kb.admin.dto.DrillResult;
import com.enterprise.kb.admin.dto.GuardrailRuleView;
import com.enterprise.kb.admin.service.GuardrailAdminService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 护栏词表运维 API（安全簇⑥ F2，专项方案 §4.6）——词表列表查询 + 命中演练。
 *
 * <p><b>租户守卫</b>：与簇③/④ 同款——{@code @AuthenticationPrincipal Jwt} 直消费
 * （kb-admin 不依赖 kb-api，不复用 JwtUtils），owner claim → tenantId，缺失
 * fail-closed IDENTITY_INCOMPLETE。鉴权经 SecurityConfig {@code /api/**}
 * authenticated 既有规则覆盖，无额外暴露面。
 *
 * <p><b>端点清单</b>：
 * <pre>
 * GET  /api/v1/admin/guardrail/rules   词表列表查询（side/family/lang/action/enabled/type 全可选）
 * POST /api/v1/admin/guardrail/drill   命中演练（输入文本 → 命中词项元数据，不计指标不落审计）
 * </pre>
 *
 * <p>视图只回词项元数据与指纹（value 明文不回显，第七节纪律）；读
 * {@link com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry} 活快照，
 * F1 热重载后即时一致。
 */
@RestController
@RequestMapping("/api/v1/admin/guardrail")
@RequiredArgsConstructor
public class GuardrailAdminController {

    private final GuardrailAdminService guardrailAdminService;

    /** 词表列表查询：侧别/族系/语种/动作/启用态/匹配类型全部可选过滤 */
    @GetMapping("/rules")
    public ApiResponse<List<GuardrailRuleView>> listRules(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String side,
        @RequestParam(required = false) String family,
        @RequestParam(required = false) String lang,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) Boolean enabled,
        @RequestParam(required = false) String type) {
        requireTenantId(jwt);
        return ApiResponse.success(
            guardrailAdminService.listRules(side, family, lang, action, enabled, type));
    }

    /** 命中演练：纯运营视图——与运行时同口径匹配，不计指标不落审计 */
    @PostMapping("/drill")
    public ApiResponse<DrillResult> drill(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody DrillRequest request) {
        requireTenantId(jwt);
        return ApiResponse.success(guardrailAdminService.drill(request.text()));
    }

    /** 租户守卫（fail-closed）：JWT 缺失或 owner claim 空白 → IDENTITY_INCOMPLETE */
    private static String requireTenantId(Jwt jwt) {
        String tenantId = jwt != null ? jwt.getClaimAsString("owner") : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
