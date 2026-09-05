package com.enterprise.kb.ai.agent.service;

import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

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
 * 编排链服务测试（簇⑤ 5.3）——对齐 ToolChatServiceTest 断言面：
 * toolContext 身份组装（无 HITL 凭证键语义）+ 确认指令恒不注入（编排链无审批）
 */
class AgentOrchestratorServiceTest {

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private AgentOrchestratorService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("编排回答");
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("编", "排"));
        service = new AgentOrchestratorService(chatClient);
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
    void chatOrchestratorAssemblesIdentityToolContext() {
        String answer = service.chatOrchestrator("任务", "session-1", ctx("tenant-a", "user-1"));

        assertThat(answer).isEqualTo("编排回答");
        Map<String, Object> toolContext = captureToolContext();
        assertThat(toolContext)
            .containsEntry(ToolContextKeys.TENANT_ID, "tenant-a")
            .containsEntry(ToolContextKeys.USER_ID, "user-1")
            .containsKey(ToolContextKeys.RETRIEVAL_CONTEXT);
    }

    @Test
    void missingUserIdConditionallyOmitted() {
        service.chatOrchestrator("任务", "session-1", ctx("tenant-a", null));

        assertThat(captureToolContext())
            .containsEntry(ToolContextKeys.TENANT_ID, "tenant-a")
            .doesNotContainKey(ToolContextKeys.USER_ID);
    }

    @Test
    void noApprovalHintEverInjected() {
        service.chatOrchestrator("任务", "session-1", ctx("tenant-a", "user-1"));

        // 编排链无 HITL——与 tool 链不同，system 确认指令恒不注入
        verify(requestSpec, never()).system(anyString());
    }

    @Test
    void streamOrchestratorReturnsTokenFlux() {
        Flux<String> tokens = service.chatStreamOrchestrator("任务", "session-1", ctx("tenant-a", "user-1"));

        assertThat(tokens.collectList().block()).containsExactly("编", "排");
        assertThat(captureToolContext()).containsEntry(ToolContextKeys.TENANT_ID, "tenant-a");
    }
}
