package com.enterprise.kb.ai.tool;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业 Mock 工具层（设计文档 11.2.1，任务 3.3）
 *
 * <p>复审定稿：OA/ERP/数据库真实系统在开发环境不可达，3.3 先定 **Mock 工具层**——
 * 内存确定性假数据，契约（工具签名/描述/返回结构）按真实系统设计，后续逐个
 * 替换为真实服务客户端而不动 Advisor 链与 HITL 机制（3.4 审批沙箱直接复用）。
 *
 * <p>工具分两类（11.2.1 安全沙箱约定）：
 * <ul>
 *   <li><b>读操作</b>——自动执行，无审批需求（本类当前全部为读工具）</li>
 *   <li><b>写操作</b>——HITL 三段式审批（3.4 加入：预检挂起 → 用户确认 → 校验执行）</li>
 * </ul>
 *
 * <p>装配：{@code agentChatClient.defaultTools(...)} 注册；2.0 GA 的
 * ChatClient 在存在工具回调时自动挂 ToolCallingAdvisor，但本项目以
 * advisorOrder(1000) 自建实例（自动注册让位，见 AgentChatClientConfig 注记）。
 * 仅挂生产链路——kb-eval 的 chatClient 不注册工具，评估基线不受工具影响。
 */
@Component
@RequiredArgsConstructor
public class EnterpriseMockTools {

    private final ToolApprovalService approvalService;

    /** Mock 员工主数据（契约对应真实 ERP 员工服务） */
    private static final Map<String, EmployeeInfo> EMPLOYEES = Map.of(
        "E1001", new EmployeeInfo("E1001", "张三", "研发部", "高级工程师", "2021-03-15"),
        "E1002", new EmployeeInfo("E1002", "李四", "财务部", "财务专员", "2022-07-01"),
        "E1003", new EmployeeInfo("E1003", "王五", "销售部", "区域销售经理", "2020-11-20"));

    /** Mock 年假余额（天） */
    private static final Map<String, Integer> LEAVE_BALANCE = Map.of(
        "E1001", 10, "E1002", 5, "E1003", 15);

    /**
     * 员工信息查询（ERP 读操作，自动执行）
     */
    @Tool(description = "根据员工工号或姓名查询员工基本信息，包括部门、职位、入职日期")
    public EmployeeInfo queryEmployee(
            @ToolParam(description = "员工工号（如 E1001）或姓名（如 张三）") String keyword) {
        // 工号精确匹配优先，其次姓名匹配
        EmployeeInfo byId = EMPLOYEES.get(keyword);
        if (byId != null) {
            return byId;
        }
        return EMPLOYEES.values().stream()
            .filter(e -> e.name().equals(keyword))
            .findFirst()
            .orElse(null);
    }

    /**
     * 年假余额查询（OA 读操作，自动执行）
     */
    @Tool(description = "根据员工工号查询该员工剩余年假天数")
    public LeaveBalance queryLeaveBalance(
            @ToolParam(description = "员工工号（如 E1001）") String employeeId) {
        Integer days = LEAVE_BALANCE.get(employeeId);
        return days == null ? null : new LeaveBalance(employeeId, days);
    }

    /**
     * 提交请假申请（OA **写操作**，HITL 三段式，任务 3.4）：
     * <ol>
     *   <li>首次调用**不落写**——创建待审批单，返回 PENDING_APPROVAL + approvalId，
     *       经 RetrievalContext 工具调用记录投影 SSE TOOL_CALL 事件（前端确认卡片）</li>
     *   <li>用户确认 → POST /api/v1/tools/approvals/{id}/approve（账本置 APPROVED）</li>
     *   <li>前端携带 approvalId 发起二次对话（请求体 approvedToolCallId → toolContext
     *       通道，复审要素②），本工具校验并一次性消费后才真正提交</li>
     * </ol>
     * 身份缺失或审批服务故障一律拒绝落写（fail-closed）。
     */
    @Tool(description = "提交员工请假申请（写操作，需用户审批）。首次调用返回待确认摘要与审批 ID，"
        + "用户确认后才会真正提交，切勿在未经用户确认时重复调用")
    public String submitLeaveRequest(
            @ToolParam(description = "员工工号（如 E1001）") String employeeId,
            @ToolParam(description = "请假开始日期 (yyyy-MM-dd)") String startDate,
            @ToolParam(description = "请假结束日期 (yyyy-MM-dd)") String endDate,
            @ToolParam(description = "请假类型: 年假/事假/病假") String leaveType,
            ToolContext toolContext) {

        Map<String, Object> ctx = toolContext.getContext();
        String tenantId = asString(ctx.get(ToolContextKeys.TENANT_ID));
        String userId = asString(ctx.get(ToolContextKeys.USER_ID));
        String approvedId = asString(ctx.get(ToolContextKeys.APPROVED_TOOL_CALL_ID));
        RetrievalContext retrievalContext =
            ctx.get(ToolContextKeys.RETRIEVAL_CONTEXT) instanceof RetrievalContext rc ? rc : null;
        String summary = "为员工 " + employeeId + " 提交 " + leaveType
            + " 请假（" + startDate + " 至 " + endDate + "）";

        // fail-closed：身份不完整不创建审批单（Controller 守卫之上的工具层纵深）
        if (tenantId == null || userId == null) {
            return "⚠️ 身份不完整，无法发起写操作审批";
        }

        if (approvedId != null) {
            boolean consumed;
            try {
                consumed = approvalService.consume(approvedId, tenantId, userId);
            } catch (BusinessException e) {
                return "⚠️ " + e.getMessage();
            }
            if (!consumed) {
                return "⚠️ 审批 ID 无效、已过期或已被使用，请重新发起请假申请并等待用户确认";
            }
            // Mock 落写（契约位：真实实现为 OA 系统提交）
            recordToolCall(retrievalContext, "submitLeaveRequest", "EXECUTED", approvedId, summary);
            return "✅ 请假申请已提交：" + summary;
        }

        String approvalId;
        try {
            approvalId = approvalService.createPending(tenantId, userId, "submitLeaveRequest", summary);
        } catch (BusinessException e) {
            return "⚠️ " + e.getMessage();
        }
        recordToolCall(retrievalContext, "submitLeaveRequest", "PENDING_APPROVAL", approvalId, summary);
        return "⏳ PENDING_APPROVAL:" + approvalId + " 待用户确认：" + summary
            + "。请将审批 ID 展示给用户，等待用户确认后再执行。";
    }

    private static void recordToolCall(RetrievalContext ctx, String toolName,
                                      String status, String approvalId, String summary) {
        if (ctx != null) {
            ctx.addToolCall(new RetrievalContext.ToolCall(toolName, status, approvalId, summary));
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 员工信息（契约 record） */
    public record EmployeeInfo(String employeeId, String name, String department,
                               String position, String hireDate) {}

    /** 年假余额（契约 record） */
    public record LeaveBalance(String employeeId, int remainingDays) {}
}
