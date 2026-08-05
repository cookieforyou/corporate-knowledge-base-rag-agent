package com.enterprise.kb.ai.agent.service;

import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具事务链服务测试（3.19）—— toolContext 通道组装（复审要素②）：
 * 身份 + RetrievalContext + approvedToolCallId 条件写入，ChatClient 无 null 值约束；
 * 确认指令仅凭证存在时注入
 */
class ToolChatServiceTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ToolChatService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("回答");
        service = new ToolChatService(chatClient);
    }

    private static RetrievalContext ctx(String tenantId, String userId) {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        ctx.setUserId(userId);
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureToolContext() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(requestSpec).toolContext(captor.capture());
        return captor.getValue();
    }

    @Test
    void toolContextCarriesIdentityAndRetrievalContext() {
        RetrievalContext ctx = ctx("tenant-a", "user-1");

        service.chatTool("查员工", "s-1", ctx, null);

        Map<String, Object> toolContext = captureToolContext();
        assertThat(toolContext.get(ToolContextKeys.RETRIEVAL_CONTEXT)).isSameAs(ctx);
        assertThat(toolContext.get(ToolContextKeys.TENANT_ID)).isEqualTo("tenant-a");
        assertThat(toolContext.get(ToolContextKeys.USER_ID)).isEqualTo("user-1");
        assertThat(toolContext).doesNotContainKey(ToolContextKeys.APPROVED_TOOL_CALL_ID);
        assertThat(toolContext.values()).doesNotContainNull();
    }

    @Test
    void approvedToolCallIdWrittenConditionallyWithConfirmHint() {
        service.chatTool("确认提交", "s-1", ctx("tenant-a", "user-1"), "apv-001");

        assertThat(captureToolContext())
            .containsEntry(ToolContextKeys.APPROVED_TOOL_CALL_ID, "apv-001");
        // 携带凭证时注入确认指令（确认轮确定化加固）
        verify(requestSpec).system(anyString());
    }

    @Test
    void noApprovalIdNoConfirmHint() {
        service.chatTool("查员工", "s-1", ctx("tenant-a", "user-1"), null);

        verify(requestSpec, never()).system(anyString());
    }

    @Test
    void nullUserIdOmittedSatisfyingNonNullConstraint() {
        service.chatTool("查员工", "s-1", ctx("tenant-a", null), null);

        assertThat(captureToolContext())
            .doesNotContainKey(ToolContextKeys.USER_ID)
            .doesNotContainValue(null);
    }
}
