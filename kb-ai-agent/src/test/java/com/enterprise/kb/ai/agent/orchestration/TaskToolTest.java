package com.enterprise.kb.ai.agent.orchestration;

import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task 委派工具测试（簇⑤ 5.3）——路由/身份下传/超时与失败文本化语义：
 * 委派失败不抛异常击穿主链，错误以文本回流主 Agent 决策
 */
class TaskToolTest {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @AfterAll
    static void tearDown() {
        EXECUTOR.shutdown();
    }

    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;
    private SubAgentClientFactory clientFactory;
    private TaskTool taskTool;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolContext(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("子代理结果");
        clientFactory = mock(SubAgentClientFactory.class);
        when(clientFactory.create(any())).thenReturn(chatClient);
        taskTool = new TaskTool(
            new SubAgentRegistry(List.of(new SubAgentSpec(
                "demo", "演示职责", "演示系统指令", List.of(), null, 60))),
            clientFactory, EXECUTOR);
    }

    private static ToolContext parentContext(RetrievalContext ctx) {
        Map<String, Object> map = new HashMap<>();
        map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        map.put(ToolContextKeys.TENANT_ID, "tenant-a");
        map.put(ToolContextKeys.USER_ID, "user-1");
        map.put(ToolContextKeys.APPROVED_TOOL_CALL_ID, "apv-should-not-pass");
        return new ToolContext(map);
    }

    @Test
    void delegatesAndRecordsExecuted() {
        RetrievalContext ctx = new RetrievalContext();

        String result = taskTool.task("demo", "查询 E1001 的信息", parentContext(ctx));

        assertThat(result).isEqualTo("子代理结果");
        assertThat(ctx.getToolCalls()).hasSize(1);
        assertThat(ctx.getToolCalls().get(0).toolName()).isEqualTo("task:demo");
        assertThat(ctx.getToolCalls().get(0).status()).isEqualTo("EXECUTED");
        assertThat(ctx.getToolCalls().get(0).summary()).contains("E1001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void identityPassedDownWithoutHitlCredential() {
        taskTool.task("demo", "任务", parentContext(new RetrievalContext()));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(requestSpec).toolContext(captor.capture());
        Map<String, Object> subContext = captor.getValue();
        assertThat(subContext)
            .containsEntry(ToolContextKeys.TENANT_ID, "tenant-a")
            .containsEntry(ToolContextKeys.USER_ID, "user-1")
            .containsKey(ToolContextKeys.RETRIEVAL_CONTEXT)
            .doesNotContainKey(ToolContextKeys.APPROVED_TOOL_CALL_ID);
    }

    @Test
    void unknownSubAgentReturnsGuidanceText() {
        String result = taskTool.task("no-such", "任务", parentContext(new RetrievalContext()));

        assertThat(result).contains("未知的子代理").contains("demo");
        verifyNoInteractions(clientFactory);
    }

    @Test
    void executionFailureTextualizedNotThrown() {
        when(callSpec.content()).thenThrow(new RuntimeException("模型服务不可用"));

        String result = taskTool.task("demo", "任务", parentContext(new RetrievalContext()));

        assertThat(result).contains("执行失败").contains("模型服务不可用");
    }

    @Test
    void executionFailureRecordedAsFailed() {
        when(callSpec.content()).thenThrow(new RuntimeException("模型服务不可用"));
        RetrievalContext ctx = new RetrievalContext();

        taskTool.task("demo", "任务", parentContext(ctx));

        assertThat(ctx.getToolCalls().get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void timeoutTextualizedAndNonInterrupting() {
        // 1s 超时 + 3s 阻塞的子调用 → TimeoutException → cancel(false) 非打断式弃任务
        TaskTool oneSecondTool = new TaskTool(
            new SubAgentRegistry(List.of(new SubAgentSpec(
                "demo", "演示职责", "演示系统指令", List.of(), null, 1))),
            spec -> blockingClient(), EXECUTOR);

        long start = System.nanoTime();
        String result = oneSecondTool.task("demo", "任务", parentContext(new RetrievalContext()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).contains("执行超时");
        assertThat(elapsedMs).isLessThan(2500);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    /** 返回阻塞 3s 的子客户端（模拟挂死的 LLM 调用） */
    private static ChatClient blockingClient() {
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.call()).thenReturn(call);
        when(call.content()).thenAnswer(invocation -> {
            Thread.sleep(3000);
            return "不应到达";
        });
        return client;
    }
}
