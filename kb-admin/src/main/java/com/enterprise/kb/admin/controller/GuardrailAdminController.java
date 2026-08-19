package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.DrillRequest;
import com.enterprise.kb.admin.dto.DrillResult;
import com.enterprise.kb.admin.dto.GuardrailRuleCreateRequest;
import com.enterprise.kb.admin.dto.GuardrailRuleEditView;
import com.enterprise.kb.admin.dto.GuardrailRuleMutationResult;
import com.enterprise.kb.admin.dto.GuardrailRuleUpdateRequest;
import com.enterprise.kb.admin.dto.GuardrailRuleView;
import com.enterprise.kb.admin.dto.ReloadResult;
import com.enterprise.kb.admin.service.GuardrailAdminService;
import com.enterprise.kb.admin.service.GuardrailRuleOpsService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * GET    /api/v1/admin/guardrail/rules        词表列表查询（side/family/lang/action/enabled/type 全可选）
 * POST   /api/v1/admin/guardrail/drill        命中演练（输入文本 → 命中词项元数据，不计指标不落审计）
 * POST   /api/v1/admin/guardrail/rules        新建词项（v2.53 DB 单轨；valueB64 编码态契约）
 * GET    /api/v1/admin/guardrail/rules/{id}   单词项详情（编辑预填，含 valueB64）
 * PUT    /api/v1/admin/guardrail/rules/{id}   更新词项（null 字段保持原值）
 * DELETE /api/v1/admin/guardrail/rules/{id}   删除词项
 * POST   /api/v1/admin/guardrail/reload       热更新触发（本地重载 + pub/sub 广播）
 * </pre>
 *
 * <p>列表视图只回词项元数据与指纹（value 明文不回显，第七节纪律）；读
 * {@link com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry} 活快照，
 * F1 热重载后即时一致。写路径（v2.53）直写 kb_guardrail_rule 唯一事实源，
 * API 契约只收 valueB64 编码态（前端编码后上送），编辑预填经单词项详情
 * 通道返回编码态由浏览器内解码——传输链路恒不承载明文。
 */
@RestController
@RequestMapping("/api/v1/admin/guardrail")
@RequiredArgsConstructor
public class GuardrailAdminController {

    private final GuardrailAdminService guardrailAdminService;
    private final GuardrailRuleOpsService guardrailRuleOpsService;

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

    /** 新建词项（v2.53 DB 单轨）：缺省 FLAG 观察档，写后本地生效 + 集群广播 + 存档导出 */
    @PostMapping("/rules")
    public ApiResponse<GuardrailRuleMutationResult> createRule(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody GuardrailRuleCreateRequest request) {
        return ApiResponse.success(guardrailRuleOpsService.create(request, requireTenantId(jwt)));
    }

    /** 单词项详情：编辑预填视图（含 valueB64 编码态 + 运营元数据） */
    @GetMapping("/rules/{id}")
    public ApiResponse<GuardrailRuleEditView> getRule(@AuthenticationPrincipal Jwt jwt,
                                                      @PathVariable String id) {
        requireTenantId(jwt);
        return ApiResponse.success(guardrailRuleOpsService.get(id));
    }

    /** 更新词项：null 字段保持原值；valueB64/type 变化重算指纹与去重 */
    @PutMapping("/rules/{id}")
    public ApiResponse<GuardrailRuleMutationResult> updateRule(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable String id,
        @Valid @RequestBody GuardrailRuleUpdateRequest request) {
        return ApiResponse.success(guardrailRuleOpsService.update(id, request, requireTenantId(jwt)));
    }

    /** 删除词项（物理删；git 存档留有历史，运营面主推停用） */
    @DeleteMapping("/rules/{id}")
    public ApiResponse<GuardrailRuleMutationResult> deleteRule(@AuthenticationPrincipal Jwt jwt,
                                                               @PathVariable String id) {
        requireTenantId(jwt);
        return ApiResponse.success(guardrailRuleOpsService.delete(id));
    }

    /** 热更新触发：本地 registry.reload() 同步执行 + pub/sub 集群广播 */
    @PostMapping("/reload")
    public ApiResponse<ReloadResult> reload(@AuthenticationPrincipal Jwt jwt) {
        requireTenantId(jwt);
        return ApiResponse.success(guardrailRuleOpsService.reload());
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
