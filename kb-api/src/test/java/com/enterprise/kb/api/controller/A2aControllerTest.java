package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.a2a.A2aAgentService;
import com.enterprise.kb.ai.agent.a2a.A2aIdentityGuard;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.api.controller.A2aController.JsonRpcRequest;
import com.enterprise.kb.api.controller.A2aController.JsonRpcResponse;
import com.enterprise.kb.api.controller.A2aController.Message;
import com.enterprise.kb.api.controller.A2aController.Params;
import com.enterprise.kb.api.controller.A2aController.Part;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2aController 协议层测试（Phase5簇⑥ 批2，v1.0 spec 语义）：版本/方法/参数
 * 校验三错误码、身份 fail-closed、SendMessage 同步应答 Task(completed) 组装、
 * contextId 生成与回显、多 part 文本拼接、异常固定话术零泄露。
 */
class A2aControllerTest {

    private static final String VERSION = "1.0";

    private A2aIdentityGuard identityGuard;
    private A2aAgentService agentService;
    private A2aController controller;

    @BeforeEach
    void setUp() {
        identityGuard = mock(A2aIdentityGuard.class);
        agentService = mock(A2aAgentService.class);
        controller = new A2aController(identityGuard, agentService);
    }

    private static JsonRpcRequest sendRequest(String method, List<Part> parts, String contextId) {
        return new JsonRpcRequest("2.0", "req-1", method,
            new Params(new Message("ROLE_USER", parts, "msg-1", contextId)));
    }

    private static JsonRpcRequest textRequest(String contextId) {
        return sendRequest("SendMessage", List.of(new Part("知识库问题")), contextId);
    }

    private static RetrievalContext context() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ctx.setUserId("user-1");
        return ctx;
    }

    @Test
    void sendMessageReturnsCompletedTask() {
        when(identityGuard.requireIdentity()).thenReturn(context());
        when(agentService.handle(anyString(), eq("ctx-1"), any())).thenReturn("答案正文 [ref-1]");

        JsonRpcResponse response = controller.handle(textRequest("ctx-1"), VERSION);

        assertThat(response.jsonrpc()).isEqualTo("2.0");
        assertThat(response.id()).isEqualTo("req-1");
        assertThat(response.error()).isNull();
        assertThat(response.result().task().id()).isNotBlank();
        assertThat(response.result().task().contextId()).isEqualTo("ctx-1");
        // v1.0 枚举 ProtoJSON SCREAMING_SNAKE（§5.5）
        assertThat(response.result().task().status().state()).isEqualTo("TASK_STATE_COMPLETED");
        // §5.6.1：ISO 8601 UTC 毫秒三位
        assertThat(response.result().task().status().timestamp()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(response.result().task().artifacts()).hasSize(1);
        assertThat(response.result().task().artifacts().get(0).name()).isEqualTo("answer");
        assertThat(response.result().task().artifacts().get(0).parts().get(0).text())
            .isEqualTo("答案正文 [ref-1]");
    }

    @Test
    void traceIdAssignedBeforeServiceDispatch() {
        when(identityGuard.requireIdentity()).thenReturn(context());
        when(agentService.handle(anyString(), anyString(), any())).thenReturn("答案");

        controller.handle(textRequest("ctx-1"), VERSION);

        // Controller 设 traceId：入口轻审计与对话链审计行凭同一 traceId 关联
        ArgumentCaptor<RetrievalContext> captor = ArgumentCaptor.forClass(RetrievalContext.class);
        verify(agentService).handle(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().getTraceId()).isNotBlank();
    }

    @Test
    void missingContextIdGeneratedAndEchoed() {
        when(identityGuard.requireIdentity()).thenReturn(context());
        when(agentService.handle(anyString(), anyString(), any())).thenReturn("答案");

        JsonRpcResponse response = controller.handle(textRequest(null), VERSION);

        // §3.4.1 MAY：客户端未带 contextId 则服务端生成回填
        assertThat(response.result().task().contextId()).isNotBlank();
        verify(agentService).handle(anyString(), org.mockito.ArgumentMatchers.argThat(id -> id != null && !id.isBlank()), any());
    }

    @Test
    void multiTextPartsJoinedWithNewline() {
        when(identityGuard.requireIdentity()).thenReturn(context());
        when(agentService.handle(anyString(), anyString(), any())).thenReturn("答案");
        JsonRpcRequest request = sendRequest("SendMessage",
            List.of(new Part("第一段"), new Part(null), new Part("第二段")), "ctx-2");

        controller.handle(request, VERSION);

        // v1.0 成员判别：无 text 的 part 跳过，多段换行拼接
        verify(agentService).handle(eq("第一段\n第二段"), anyString(), any());
    }

    @Test
    void missingVersionHeaderRejectedAsV03() {
        JsonRpcResponse response = controller.handle(textRequest("ctx-1"), null);

        // §3.6.2 MUST：空值按 0.3 解释，仅支持 1.0 → -32009
        assertThat(response.result()).isNull();
        assertThat(response.error().code()).isEqualTo(-32009);
        verify(agentService, never()).handle(anyString(), anyString(), any());
    }

    @Test
    void unsupportedVersionRejected() {
        JsonRpcResponse response = controller.handle(textRequest("ctx-1"), "0.3");

        assertThat(response.error().code()).isEqualTo(-32009);
        assertThat(response.error().message()).contains("1.0");
    }

    @Test
    void unknownMethodNotFound() {
        JsonRpcResponse response = controller.handle(sendRequest("GetTask",
            List.of(new Part("x")), "ctx-1"), VERSION);

        // 最小形态只实现 SendMessage；其余（GetTask/流式/推送配置族）-32601
        assertThat(response.error().code()).isEqualTo(-32601);
        verify(agentService, never()).handle(anyString(), anyString(), any());
    }

    @Test
    void missingParamsInvalidParams() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", "req-1", "SendMessage", null);

        JsonRpcResponse response = controller.handle(request, VERSION);

        assertThat(response.error().code()).isEqualTo(-32602);
    }

    @Test
    void noTextPartInvalidParams() {
        JsonRpcRequest request = sendRequest("SendMessage", List.of(new Part(null)), "ctx-1");

        JsonRpcResponse response = controller.handle(request, VERSION);

        // 仅支持 text part（最小形态）；文件/数据 part 无 text 成员即拒
        assertThat(response.error().code()).isEqualTo(-32602);
        verify(identityGuard, never()).requireIdentity();
    }

    @Test
    void identityIncompleteInternalErrorWithoutLeak() {
        when(identityGuard.requireIdentity()).thenThrow(
            new BusinessException(Constants.ErrorCodes.IDENTITY_INCOMPLETE, "内部细节"));

        JsonRpcResponse response = controller.handle(textRequest("ctx-1"), VERSION);

        // 身份缺失等治理拒绝统一固定话术——内部细节零泄露
        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(response.error().message()).doesNotContain("内部细节").isEqualTo("请求无法处理");
    }

    @Test
    void rateLimitedInternalErrorWithDistinctiveMessage() {
        when(identityGuard.requireIdentity()).thenReturn(context());
        when(agentService.handle(anyString(), anyString(), any()))
            .thenThrow(new BusinessException(Constants.ErrorCodes.RATE_LIMITED, "限流"));

        JsonRpcResponse response = controller.handle(textRequest("ctx-1"), VERSION);

        // RATE_LIMITED 语义可辨（机对机 client 可实现退避），其余固定话术
        assertThat(response.error().code()).isEqualTo(-32603);
        assertThat(response.error().message()).isEqualTo("请求过于频繁，请稍后再试");
    }
}
