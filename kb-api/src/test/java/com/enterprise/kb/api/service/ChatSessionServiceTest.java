package com.enterprise.kb.api.service;

import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话归档服务测试（3.1）
 *
 * <p>覆盖：会话首建（标题截断/身份兜底）、并发首建主键冲突容忍、
 * 一轮对话双消息落库与计数自增、归档失败不扩散。
 */
class ChatSessionServiceTest {

    private final KbSessionRepository sessionRepository = mock(KbSessionRepository.class);
    private final KbMessageRepository messageRepository = mock(KbMessageRepository.class);
    private final ChatSessionService service = new ChatSessionService(sessionRepository, messageRepository);

    @Test
    void firstTurnCreatesSessionAndArchivesTwoMessages() {
        when(sessionRepository.existsById("s1")).thenReturn(false);

        service.archiveTurn("s1", "tenant-a", "user-1", "什么是增值税发票？", "增值税发票是……");

        ArgumentCaptor<KbSession> sessionCaptor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        KbSession session = sessionCaptor.getValue();
        assertThat(session.getId()).isEqualTo("s1");
        assertThat(session.getTenantId()).isEqualTo("tenant-a");
        assertThat(session.getUserId()).isEqualTo("user-1");
        assertThat(session.getTitle()).isEqualTo("什么是增值税发票？");

        ArgumentCaptor<KbMessage> messageCaptor = ArgumentCaptor.forClass(KbMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
        List<KbMessage> messages = messageCaptor.getAllValues();
        assertThat(messages).extracting(KbMessage::getRole).containsExactly("USER", "ASSISTANT");
        assertThat(messages).allSatisfy(m -> assertThat(m.getSessionId()).isEqualTo("s1"));
        assertThat(messages.get(0).getContent()).isEqualTo("什么是增值税发票？");
        assertThat(messages.get(1).getContent()).isEqualTo("增值税发票是……");

        verify(sessionRepository).incrementMessageCount("s1", 2);
    }

    @Test
    void existingSessionNotRecreated() {
        when(sessionRepository.existsById("s1")).thenReturn(true);

        service.archiveTurn("s1", "tenant-a", "user-1", "问题", "回答");

        verify(sessionRepository, never()).save(any(KbSession.class));
        verify(messageRepository, org.mockito.Mockito.times(2)).save(any(KbMessage.class));
    }

    @Test
    void concurrentFirstTurnPrimaryKeyConflictTolerated() {
        when(sessionRepository.existsById("s1")).thenReturn(false);
        when(sessionRepository.save(any(KbSession.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // 主键冲突只影响会话创建，消息归档照常
        assertThatCode(() -> service.archiveTurn("s1", "t", "u", "问题", "回答"))
            .doesNotThrowAnyException();
        verify(messageRepository, org.mockito.Mockito.times(2)).save(any(KbMessage.class));
    }

    @Test
    void titleTruncatedForLongFirstQuery() {
        when(sessionRepository.existsById(anyString())).thenReturn(false);
        String longQuery = "这是一个非常长的首问".repeat(20);

        service.archiveTurn("s2", "t", "u", longQuery, "回答");

        ArgumentCaptor<KbSession> captor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(51).endsWith("…");
    }

    @Test
    void nullIdentityFallsBackToPlaceholder() {
        when(sessionRepository.existsById(anyString())).thenReturn(false);

        service.archiveTurn("s3", null, null, "问题", "回答");

        ArgumentCaptor<KbSession> captor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("unknown");
        assertThat(captor.getValue().getUserId()).isEqualTo("unknown");
    }

    @Test
    void repositoryFailureDoesNotPropagate() {
        when(sessionRepository.existsById(anyString())).thenReturn(true);
        when(messageRepository.save(any(KbMessage.class))).thenThrow(new RuntimeException("PG 连接失败"));

        assertThatCode(() -> service.archiveTurn("s1", "t", "u", "问题", "回答"))
            .doesNotThrowAnyException();
        verify(sessionRepository, never()).incrementMessageCount(anyString(), eq(2));
    }
}
