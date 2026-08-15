package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.AuditLogPage;
import com.enterprise.kb.admin.dto.ReingestRequest;
import com.enterprise.kb.admin.dto.ReingestResult;
import com.enterprise.kb.admin.dto.ResolvedRequest;
import com.enterprise.kb.admin.dto.RootCauseRequest;
import com.enterprise.kb.admin.service.AuditLogQueryService;
import com.enterprise.kb.admin.service.BadCaseService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bad Case 运营闭环 API（Phase 4 簇④ 4.7）——审计日志查询 + 根因标注 + Golden 回灌 + 处理态。
 *
 * <p><b>租户守卫</b>：与簇③ 同款——{@code @AuthenticationPrincipal Jwt} 直消费
 * （kb-admin 不依赖 kb-api，不复用 JwtUtils），owner claim → tenantId，缺失
 * fail-closed IDENTITY_INCOMPLETE。
 *
 * <p><b>端点清单</b>：
 * <pre>
 * GET  /api/v1/admin/audit-logs            审计多选项分页查询
 * PUT  /api/v1/admin/audit-logs/{id}/root-cause   根因标注
 * POST /api/v1/admin/badcase/reingest      Golden Set 回灌
 * PUT  /api/v1/admin/feedback/{id}/resolved       反馈处理态闭环
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class BadCaseAdminController {

    private final AuditLogQueryService auditLogQueryService;
    private final BadCaseService badCaseService;

    /** 审计日志多选项分页查询（时间/用户/会话/反馈/状态/根因/标注态，全部可选） */
    @GetMapping("/audit-logs")
    public ApiResponse<AuditLogPage> search(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String sessionId,
        @RequestParam(required = false) String feedback,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String rootCause,
        @RequestParam(required = false) Boolean annotated,
        @RequestParam(required = false) Integer page,
        @RequestParam(required = false) Integer size) {
        return ApiResponse.success(auditLogQueryService.search(requireTenantId(jwt),
            from, to, userId, sessionId, feedback, status, rootCause, annotated, page, size));
    }

    /** 根因标注：四分类写入审计行 */
    @PutMapping("/audit-logs/{auditLogId}/root-cause")
    public ApiResponse<Map<String, Object>> annotate(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable Long auditLogId,
                                                     @Valid @RequestBody RootCauseRequest request) {
        String applied = badCaseService.annotate(requireTenantId(jwt), auditLogId, request.rootCause());
        return ApiResponse.success(Map.of("auditLogId", auditLogId, "rootCause", applied));
    }

    /** Golden Set 回灌：审计行 → badcase-qa.json（upsert），联动反馈 resolved */
    @PostMapping("/badcase/reingest")
    public ApiResponse<ReingestResult> reingest(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody ReingestRequest request) {
        return ApiResponse.success(badCaseService.reingest(requireTenantId(jwt), request));
    }

    /** 反馈处理态更新（Bad Case 人工闭环标记） */
    @PutMapping("/feedback/{feedbackId}/resolved")
    public ApiResponse<Map<String, Object>> resolve(@AuthenticationPrincipal Jwt jwt,
                                                    @PathVariable String feedbackId,
                                                    @Valid @RequestBody ResolvedRequest request) {
        boolean resolved = badCaseService.resolveFeedback(requireTenantId(jwt), feedbackId, request.resolved());
        return ApiResponse.success(Map.of("feedbackId", feedbackId, "resolved", resolved));
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
