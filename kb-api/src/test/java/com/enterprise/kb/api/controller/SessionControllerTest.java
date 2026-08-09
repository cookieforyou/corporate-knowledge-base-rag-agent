package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.HistoryMessageItem;
import com.enterprise.kb.api.dto.SessionItem;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 历史会话 Controller 测试（3.15 补齐）
 *
 * <p>覆盖：身份完整性守卫（tenantId 缺失 fail-closed，与 AgentController 同纪律）、
 * 三端点参数透传。数据面归属校验在 ChatSessionService 层覆盖（ChatSessionServiceTest）。
 */
class SessionControllerTest {

    private final ChatSessionService chatSessionService = mock(ChatSessionService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private SessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionController(chatSessionService, jwtUtils);
        when(jwtUtils.getCurrentUserId()).thenReturn("user-1");
    }

    @Test
    void listWithoutTenantIdentity_rejectedBeforeService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);

        assertThatThrownBy(() -> controller.listSessions(0, 50))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(chatSessionService);
    }

    @Test
    void messagesWithoutTenantIdentity_rejectedBeforeService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("  ");

        assertThatThrownBy(() -> controller.sessionMessages("s1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(chatSessionService);
    }

    @Test
    void deleteWithoutTenantIdentity_rejectedBeforeService() {
        when(jwtUtils.getCurrentTenantId()).thenReturn(null);

        assertThatThrownBy(() -> controller.deleteSession("s1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
        verifyNoInteractions(chatSessionService);
    }

    @Test
    void listPassesIdentityAndPagingThrough() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(chatSessionService.listSessions(anyString(), anyString(), anyInt(), anyInt()))
            .thenReturn(List.of(new SessionItem("s1", "标题", 4, null)));

        List<SessionItem> data = controller.listSessions(2, 30).data();

        verify(chatSessionService).listSessions("tenant-a", "user-1", 2, 30);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).id()).isEqualTo("s1");
    }

    @Test
    void messagesPassesIdentityThrough() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");
        when(chatSessionService.loadMessages(anyString(), anyString(), anyString()))
            .thenReturn(List.of(new HistoryMessageItem("m1", "USER", "问题", null, null, null, null)));

        List<HistoryMessageItem> data = controller.sessionMessages("s1").data();

        verify(chatSessionService).loadMessages("s1", "tenant-a", "user-1");
        assertThat(data.get(0).content()).isEqualTo("问题");
    }

    @Test
    void deletePassesIdentityThroughAndReportsDeleted() {
        when(jwtUtils.getCurrentTenantId()).thenReturn("tenant-a");

        Map<String, Object> data = controller.deleteSession("s1").data();

        verify(chatSessionService).deleteSession(eq("s1"), eq("tenant-a"), eq("user-1"));
        assertThat(data).containsEntry("deleted", true);
    }
}
