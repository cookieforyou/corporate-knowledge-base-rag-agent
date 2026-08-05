package com.enterprise.kb.ai.agent.tool;

/**
 * toolContext 通道键常量（任务 3.4，复审要素②）
 *
 * <p>确认态与身份信息经 ChatClient 的 {@code .toolContext(Map)} 通道传递——
 * 与 advisor 参数是两条独立通道（advisor 参数进 request.context()，toolContext
 * 进 ToolCallingChatOptions 随工具执行注入 {@code ToolContext.getContext()}）。
 * ChatService 统一组装，工具侧只读。
 */
public final class ToolContextKeys {

    /** RetrievalContext 实例（工具写回工具调用记录，SSE TOOL_CALL 数据源） */
    public static final String RETRIEVAL_CONTEXT = "kb.retrieval_context";

    /** 租户 ID（审批绑定校验用，fail-closed 身份链路的工具层延伸） */
    public static final String TENANT_ID = "kb.tenant_id";

    /** 用户 ID（审批绑定校验用） */
    public static final String USER_ID = "kb.user_id";

    /** 已确认的审批 ID（前端确认后随二次对话回传，工具校验并一次性消费） */
    public static final String APPROVED_TOOL_CALL_ID = "kb.approved_tool_call_id";

    private ToolContextKeys() {
    }
}
