package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.service.AgentOrchestratorService;
import com.enterprise.kb.ai.agent.service.ToolChatService;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 三链路 mode 路由测试（3.19 双链路 + 簇⑤ 5.3 第三链）——缺省兼容 /
 * 显式分流（rag|tool|agent）/ 非法值拒绝 / rag 模式杂散 HITL 凭证忽略 /
 * 编排链开关守卫（enabled=false 显式 400 不静默回落）
 */
class AgentControllerModeRoutingTest {

    private final RagChatService ragChatService = mock(RagChatService.class);
    private final ToolChatService toolChatService = mock(ToolChatService.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentOrchestratorService> orchestratorProvider =
        mock(ObjectProvider.class);
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(ragChatService, toolChatService, orchestratorProvider, chatSessionService, jwtUtils,
            ObservationRegistry.create(), new AiBusinessMetrics(new SimpleMeterRegistry()), PiiRecognizerRegistry.defaults());
        when(jwtUtils.getCurrentUsername()).thenReturn("user_test");
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
    }

    @Test
    void defaultModeRoutesToRagChain() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("rag 回答");

        controller.chat(Map.of("query", "问题"));

        verify(ragChatService).chatRag(anyString(), anyString(), any());
        verifyNoInteractions(toolChatService);
    }

    @Test
    void explicitToolModeRoutesToToolChain() {
        when(toolChatService.chatTool(anyString(), anyString(), any(), isNull())).thenReturn("tool 回答");

        Map<String, Object> data = controller.chat(Map.of("query", "查员工", "mode", "tool")).data();

        assertThat(data).containsEntry("answer", "tool 回答");
        verifyNoInteractions(ragChatService);
    }

    @Test
    void modeIsCaseInsensitive() {
        when(toolChatService.chatTool(anyString(), anyString(), any(), isNull())).thenReturn("tool 回答");

        controller.chat(Map.of("query", "查员工", "mode", "TOOL"));

        verify(toolChatService).chatTool(anyString(), anyString(), any(), isNull());
    }

    @Test
    void approvedToolCallIdForwardedOnlyInToolMode() {
        when(toolChatService.chatTool(anyString(), anyString(), any(), anyString())).thenReturn("已提交");

        controller.chat(Map.of("query", "确认", "mode", "tool", "approvedToolCallId", "apv-001"));

        verify(toolChatService).chatTool(anyString(), anyString(), any(), ArgumentMatchers.eq("apv-001"));
    }

    @Test
    void ragModeIgnoresStrayApprovalId() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("rag 回答");

        controller.chat(Map.of("query", "问题", "mode", "rag", "approvedToolCallId", "apv-001"));

        // rag 链签名无 approvedToolCallId——物理隔离，凭证不可能流入
        verify(ragChatService).chatRag(anyString(), anyString(), any());
        verifyNoInteractions(toolChatService);
    }

    @Test
    void agentModeRoutesToOrchestratorChain() {
        AgentOrchestratorService orchestrator = mock(AgentOrchestratorService.class);
        when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);
        when(orchestrator.chatOrchestrator(anyString(), anyString(), any())).thenReturn("编排回答");

        Map<String, Object> data = controller.chat(Map.of("query", "综合任务", "mode", "agent")).data();

        assertThat(data).containsEntry("answer", "编排回答");
        verifyNoInteractions(ragChatService, toolChatService);
    }

    @Test
    void agentModeRejectedWhenOrchestratorDisabled() {
        when(orchestratorProvider.getIfAvailable()).thenReturn(null);

        // 开关关闭态显式拒绝（D3 定案），不静默回落 tool 改变语义
        assertThatThrownBy(() -> controller.chat(Map.of("query", "任务", "mode", "agent")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("ORCHESTRATOR_DISABLED");
        verifyNoInteractions(ragChatService, toolChatService);
    }

    @Test
    void agentModeIgnoresStrayApprovalId() {
        AgentOrchestratorService orchestrator = mock(AgentOrchestratorService.class);
        when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);
        when(orchestrator.chatOrchestrator(anyString(), anyString(), any())).thenReturn("编排回答");

        // 编排链无 HITL 消费方——凭证与 rag 链同语义忽略告警，不阻断问答
        controller.chat(Map.of("query", "任务", "mode", "agent", "approvedToolCallId", "apv-001"));

        verify(orchestrator).chatOrchestrator(anyString(), anyString(), any());
    }

    @Test
    void invalidModeRejected() {
        assertThatThrownBy(() -> controller.chat(Map.of("query", "问题", "mode", "invalid")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("INVALID_MODE");
        verifyNoInteractions(ragChatService);
        verifyNoInteractions(toolChatService);
    }
}
