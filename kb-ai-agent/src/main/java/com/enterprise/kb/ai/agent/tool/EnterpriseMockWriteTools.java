package com.enterprise.kb.ai.agent.tool;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 企业 Mock 写工具（簇⑤ 5.3 自 EnterpriseMockTools 拆分——读/写半区隔离）
 *
 * <p>HITL 三段式（11.2.1 任务 3.4 定稿，机制层不依赖 Mock 数据）：
 * <ol>
 *   <li>首次调用<b>不落写</b>——创建待审批单，返回 PENDING_APPROVAL + approvalId，
 *       经 RetrievalContext 工具调用记录投影 SSE TOOL_CALL 事件（前端确认卡片）</li>
 *   <li>用户确认 → POST /api/v1/tools/approvals/{id}/approve（账本置 APPROVED）</li>
 *   <li>前端携带 approvalId 发起二次对话（请求体 approvedToolCallId → toolContext
 *       通道），本工具校验并一次性消费后才真正提交</li>
 * </ol>
 * 身份缺失或审批服务故障一律拒绝落写（fail-closed）。编排链（mode=agent）不挂
 * 本类——跨层 HITL 为真实工具升级路径（§11.5.5 契约）。
 */
@Component
@RequiredArgsConstructor
public class EnterpriseMockWriteTools {

    private final ToolApprovalService approvalService;

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
            recordToolCall(retrievalContext, "submitLeaveRequest", RetrievalContext.ToolCall.STATUS_EXECUTED, approvedId, summary);
            return "✅ 请假申请已提交：" + summary;
        }

        String approvalId;
        try {
            approvalId = approvalService.createPending(tenantId, userId, "submitLeaveRequest", summary);
        } catch (BusinessException e) {
            return "⚠️ " + e.getMessage();
        }
        recordToolCall(retrievalContext, "submitLeaveRequest", RetrievalContext.ToolCall.STATUS_PENDING_APPROVAL, approvalId, summary);
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
}
