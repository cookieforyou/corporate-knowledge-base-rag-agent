package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.agent.service.ToolChatService;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 身份完整性守卫测试（3.9+3.10 安全收敛）
 *
 * <p>HTTP 层 SecurityConfig 只保证「已认证」；token 的 owner claim 可能缺失致
 * tenantId 为 null。守卫必须 fail-closed 拒绝——检索层无租户过滤即跨租户全量可见。
 */
class AgentControllerTenantGuardTest {

    private final RagChatService ragChatService = mock(RagChatService.class);
    private final ToolChatService toolChatService = mock(ToolChatService.class);
    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private AgentController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentController(ragChatService, toolChatService, chatSessionService, jwtUtils);
        when(jwtUtils.getCurrentUsername()).thenReturn("user_test");
    }

    @Test
    void chatWithoutTenantIdentity_rejectedBeforeAnyService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");

        assertThatThrownBy(() -> controller.chat(Map.of("query", "问题")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(ragChatService);
        verifyNoInteractions(toolChatService);
        verifyNoInteractions(chatSessionService);
    }

    @Test
    void chatStreamWithoutTenantIdentity_rejectedBeforeAnyService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("  ");   // 空白同样拒绝
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");

        assertThatThrownBy(() -> controller.chatStream(Map.of("query", "问题")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(ragChatService);
        verifyNoInteractions(toolChatService);
    }

    @Test
    void chatWithTenantIdentity_passesThrough() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("回答");

        Map<String, Object> response = controller.chat(Map.of("query", "问题")).data();

        assertThat(response).containsEntry("answer", "回答");
        assertThat(response).containsKey("sessionId");
    }

    /** PG 归档与模型上下文同规则脱敏——PII 不得绕过护栏落 kb_message */
    @Test
    void chatArchivesSanitizedQuery() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("回答");

        controller.chat(Map.of("query", "我的手机号是 13911112222", "sessionId", "s-pii"));

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatSessionService).archiveTurn(
            ArgumentMatchers.eq("s-pii"), anyString(), anyString(),
            queryCaptor.capture(), anyString(), anyString(), any(), anyString());
        assertThat(queryCaptor.getValue())
            .contains("1***-****-****")
            .doesNotContain("13911112222");
    }
}
