package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.tool.ToolApprovalService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具审批 API 测试（3.4）—— 身份守卫 + 确认透传
 */
class ToolApprovalControllerTest {

    private ToolApprovalService approvalService;
    private JwtUtils jwtUtils;
    private ToolApprovalController controller;

    @BeforeEach
    void setUp() {
        approvalService = mock(ToolApprovalService.class);
        jwtUtils = mock(JwtUtils.class);
        controller = new ToolApprovalController(approvalService, jwtUtils);
    }

    @Test
    void approvePassesIdentityToService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
        when(approvalService.approve("apv-001", "tenant-a", "user-1")).thenReturn(true);

        assertThat(controller.approve("apv-001").data()).containsEntry("approved", true);
        verify(approvalService).approve("apv-001", "tenant-a", "user-1");
    }

    @Test
    void approveReflectedFalseForForeignApproval() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-b");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-2");
        when(approvalService.approve("apv-001", "tenant-b", "user-2")).thenReturn(false);

        assertThat(controller.approve("apv-001").data()).containsEntry("approved", false);
    }

    @Test
    void missingTenantRejectedFailClosed() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("");

        assertThatThrownBy(() -> controller.approve("apv-001"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("IDENTITY_INCOMPLETE");
    }
}
