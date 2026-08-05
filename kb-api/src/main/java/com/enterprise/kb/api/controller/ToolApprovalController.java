package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.tool.ToolApprovalService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工具审批 API（设计文档 11.2.1 HITL 三段式第二段，任务 3.4）
 *
 * <p>用户在对话流中收到 PENDING_APPROVAL 确认卡片（SSE TOOL_CALL 事件携带
 * approvalId）后，前端调用本端点确认；账本置 APPROVED 后，前端携带
 * approvalId 发起二次对话（请求体 approvedToolCallId）触发写工具真正执行。
 *
 * <p>安全形态：审批单与发起时 tenant/user 绑定，本端点以当前 JWT 身份校验
 * 绑定——跨租户/跨用户确认返回 approved=false（不暴露失败细节，防探测）；
 * 身份不完整同 3.9 fail-closed 守卫拒绝。
 */
@RestController
@RequestMapping("/api/v1/tools/approvals")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalService approvalService;
    private final JwtUtils jwtUtils;

    @PostMapping("/{approvalId}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable String approvalId) {
        String tenantId = jwtUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        boolean approved = approvalService.approve(approvalId, tenantId, jwtUtils.getCurrentUserId());
        return ApiResponse.success(Map.of("approved", approved));
    }
}
