package com.enterprise.kb.ai.tool;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock 工具层测试（3.3/3.4）—— 读工具契约 + 写工具 HITL 三段式
 * （预检挂起 / 校验消费执行 / 越权与故障 fail-closed）
 */
class EnterpriseMockToolsTest {

    private ToolApprovalService approvalService;
    private EnterpriseMockTools tools;

    @BeforeEach
    void setUp() {
        approvalService = mock(ToolApprovalService.class);
        tools = new EnterpriseMockTools(approvalService);
    }

    // ── 读工具（3.3）──

    @Test
    void queryEmployeeById() {
        EnterpriseMockTools.EmployeeInfo info = tools.queryEmployee("E1001");

        assertThat(info).isNotNull();
        assertThat(info.name()).isEqualTo("张三");
        assertThat(info.department()).isEqualTo("研发部");
        assertThat(info.position()).isEqualTo("高级工程师");
        assertThat(info.hireDate()).isEqualTo("2021-03-15");
    }

    @Test
    void queryEmployeeByName() {
        EnterpriseMockTools.EmployeeInfo info = tools.queryEmployee("李四");

        assertThat(info).isNotNull();
        assertThat(info.employeeId()).isEqualTo("E1002");
        assertThat(info.department()).isEqualTo("财务部");
    }

    @Test
    void queryEmployeeUnknownReturnsNull() {
        assertThat(tools.queryEmployee("不存在的人")).isNull();
    }

    @Test
    void queryLeaveBalance() {
        EnterpriseMockTools.LeaveBalance balance = tools.queryLeaveBalance("E1003");

        assertThat(balance).isNotNull();
        assertThat(balance.remainingDays()).isEqualTo(15);
    }

    @Test
    void queryLeaveBalanceUnknownReturnsNull() {
        assertThat(tools.queryLeaveBalance("E9999")).isNull();
    }

    // ── 写工具 HITL（3.4）──

    private static ToolContext toolContext(RetrievalContext ctx, String approvedId) {
        Map<String, Object> map = new HashMap<>();
        map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        map.put(ToolContextKeys.TENANT_ID, "tenant-a");
        map.put(ToolContextKeys.USER_ID, "user-1");
        if (approvedId != null) {
            map.put(ToolContextKeys.APPROVED_TOOL_CALL_ID, approvedId);
        }
        return new ToolContext(map);
    }

    @Test
    void firstCallSuspendsWithPendingApproval() {
        when(approvalService.createPending(eq("tenant-a"), eq("user-1"),
            eq("submitLeaveRequest"), anyString())).thenReturn("apv-001");
        RetrievalContext ctx = new RetrievalContext();

        String result = tools.submitLeaveRequest(
            "E1001", "2026-08-10", "2026-08-12", "年假", toolContext(ctx, null));

        assertThat(result).startsWith("⏳ PENDING_APPROVAL:apv-001");
        assertThat(ctx.getToolCalls()).hasSize(1);
        assertThat(ctx.getToolCalls().get(0).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(ctx.getToolCalls().get(0).approvalId()).isEqualTo("apv-001");
        assertThat(ctx.getToolCalls().get(0).summary()).contains("E1001").contains("年假");
    }

    @Test
    void confirmedApprovalExecutesAndConsumesOnce() {
        when(approvalService.consume("apv-001", "tenant-a", "user-1")).thenReturn(true);
        RetrievalContext ctx = new RetrievalContext();

        String result = tools.submitLeaveRequest(
            "E1001", "2026-08-10", "2026-08-12", "年假", toolContext(ctx, "apv-001"));

        assertThat(result).startsWith("✅ 请假申请已提交");
        verify(approvalService).consume("apv-001", "tenant-a", "user-1");
        assertThat(ctx.getToolCalls().get(0).status()).isEqualTo("EXECUTED");
    }

    @Test
    void invalidApprovalIdRefused() {
        when(approvalService.consume("apv-expired", "tenant-a", "user-1")).thenReturn(false);

        String result = tools.submitLeaveRequest(
            "E1001", "2026-08-10", "2026-08-12", "年假", toolContext(new RetrievalContext(), "apv-expired"));

        assertThat(result).contains("审批 ID 无效");
    }

    @Test
    void missingIdentityRefusedFailClosed() {
        Map<String, Object> noIdentity = new HashMap<>();
        noIdentity.put(ToolContextKeys.RETRIEVAL_CONTEXT, new RetrievalContext());

        String result = tools.submitLeaveRequest(
            "E1001", "2026-08-10", "2026-08-12", "年假", new ToolContext(noIdentity));

        assertThat(result).contains("身份不完整");
    }

    @Test
    void approvalStoreFailureRefusedFailClosed() {
        when(approvalService.createPending(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new BusinessException("APPROVAL_STORE_UNAVAILABLE", "审批服务暂不可用，请稍后再试"));

        String result = tools.submitLeaveRequest(
            "E1001", "2026-08-10", "2026-08-12", "年假", toolContext(new RetrievalContext(), null));

        assertThat(result).contains("审批服务暂不可用");
    }
}
