package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.FeedbackExportSummary;
import com.enterprise.kb.admin.service.FeedbackExportService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 反馈微调数据导出 API（簇② 5.10 批4）——kb_feedback + trace 关联的
 * JSONL 双格式导出（SFT 单轮 / DPO 偏好对），只导出不绑定平台。
 *
 * <p><b>租户守卫</b>：与簇③/④ 同款——{@code @AuthenticationPrincipal Jwt}
 * 直消费，owner claim → tenantId，缺失 fail-closed IDENTITY_INCOMPLETE。
 *
 * <p><b>端点清单</b>：
 * <pre>
 * GET /api/v1/admin/feedback/export/summary     导出概览（计数 + 门槛对照，零内容）
 * GET /api/v1/admin/feedback/export?format=sft  JSONL 附件下载（sft|dpo）
 * </pre>
 *
 * <p>导出为纯读旁路：不改变反馈/审计任何状态；内容已经租户收敛 +
 * PII 掩码 + 审计三态过滤（{@link FeedbackExportService}）。
 */
@RestController
@RequestMapping("/api/v1/admin/feedback/export")
@RequiredArgsConstructor
public class FeedbackExportAdminController {

    /** NDJSON MIME（JSONL 逐行 JSON 附件） */
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");

    private final FeedbackExportService feedbackExportService;

    /** 导出概览（dry-run）：双格式可导出计数 + 百炼门槛对照 + 跳过原因分解 */
    @GetMapping("/summary")
    public ApiResponse<FeedbackExportSummary> summary(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(feedbackExportService.summary(requireTenantId(jwt)));
    }

    /** JSONL 导出：format=sft（缺省）|dpo，附件下载，文件名为 格式-租户-日期 */
    @GetMapping
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false, defaultValue = "sft") String format) {
        String tenantId = requireTenantId(jwt);
        FeedbackExportService.ExportFormat parsed = FeedbackExportService.ExportFormat.parse(format);
        if (parsed == null) {
            throw new BusinessException("INVALID_EXPORT_FORMAT",
                "不支持的导出格式: " + format + "（合法值: sft|dpo）");
        }
        List<String> lines = feedbackExportService.exportLines(tenantId, parsed);
        String fileName = "kb-feedback-" + parsed.name().toLowerCase() + "-"
            + sanitizeForFileName(tenantId) + "-" + LocalDate.now() + ".jsonl";
        return ResponseEntity.ok()
            .contentType(NDJSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
            .body(FeedbackExportService.toJsonlBytes(lines));
    }

    /** 租户守卫（fail-closed）：JWT 缺失或 owner claim 空白 → IDENTITY_INCOMPLETE */
    private static String requireTenantId(Jwt jwt) {
        String tenantId = jwt != null ? jwt.getClaimAsString("owner") : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }

    /** 文件名安全化：仅保留字母数字与 -_（租户 ID 入文件名防注入路径分隔符） */
    private static String sanitizeForFileName(String tenantId) {
        return tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
