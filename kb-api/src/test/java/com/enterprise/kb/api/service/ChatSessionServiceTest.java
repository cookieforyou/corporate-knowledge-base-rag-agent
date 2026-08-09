package com.enterprise.kb.api.service;

import com.enterprise.kb.api.dto.AgentStreamEvent;
import com.enterprise.kb.api.dto.HistoryMessageItem;
import com.enterprise.kb.api.dto.SessionItem;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.FeedbackRating;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话归档与历史会话服务测试（3.1 归档；3.15 补齐：列表/消息/删除/回填，v2.17）
 *
 * <p>覆盖：会话首建（标题截断/身份兜底）、并发首建主键冲突容忍、
 * 一轮对话双消息落库与计数自增、归档失败不扩散、citations/metadata 落库、
 * 记忆回填四象限 + 守卫、会话列表分页钳制、归属校验 fail-closed、反馈回显。
 */
class ChatSessionServiceTest {

    private final KbSessionRepository sessionRepository = mock(KbSessionRepository.class);
    private final KbMessageRepository messageRepository = mock(KbMessageRepository.class);
    private final KbFeedbackRepository feedbackRepository = mock(KbFeedbackRepository.class);
    private final ChatMemory agentChatMemory = mock(ChatMemory.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ChatSessionService service = new ChatSessionService(
        sessionRepository, messageRepository, feedbackRepository,
        agentChatMemory, redissonClient, jsonMapper);

    // ── 归档（3.1 既有覆盖）──

    @Test
    void firstTurnCreatesSessionAndArchivesTwoMessages() {
        when(sessionRepository.existsById("s1")).thenReturn(false);

        service.archiveTurn("s1", "tenant-a", "user-1", "什么是增值税发票？", "增值税发票是……", null, null, null);

        ArgumentCaptor<KbSession> sessionCaptor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(sessionCaptor.capture());
        KbSession session = sessionCaptor.getValue();
        assertThat(session.getId()).isEqualTo("s1");
        assertThat(session.getTenantId()).isEqualTo("tenant-a");
        assertThat(session.getUserId()).isEqualTo("user-1");
        assertThat(session.getTitle()).isEqualTo("什么是增值税发票？");

        ArgumentCaptor<KbMessage> messageCaptor = ArgumentCaptor.forClass(KbMessage.class);
        verify(messageRepository, Mockito.times(2)).save(messageCaptor.capture());
        List<KbMessage> messages = messageCaptor.getAllValues();
        assertThat(messages).extracting(KbMessage::getRole).containsExactly("USER", "ASSISTANT");
        assertThat(messages).allSatisfy(m -> assertThat(m.getSessionId()).isEqualTo("s1"));
        assertThat(messages.get(0).getContent()).isEqualTo("什么是增值税发票？");
        assertThat(messages.get(1).getContent()).isEqualTo("增值税发票是……");

        verify(sessionRepository).incrementMessageCount("s1", 2);
    }

    /** 3.17：助手消息 ID 由 Controller 请求线程预生成，归档复用——反馈外键可解析 */
    @Test
    void preGeneratedAssistantMessageIdReused() {
        when(sessionRepository.existsById("s1")).thenReturn(true);

        service.archiveTurn("s1", "tenant-a", "user-1", "问题", "回答", "msg-assistant-001", null, null);

        ArgumentCaptor<KbMessage> messageCaptor = ArgumentCaptor.forClass(KbMessage.class);
        verify(messageRepository, Mockito.times(2)).save(messageCaptor.capture());
        List<KbMessage> messages = messageCaptor.getAllValues();
        assertThat(messages.get(0).getId()).isNotBlank().isNotEqualTo("msg-assistant-001");
        assertThat(messages.get(1).getId()).isEqualTo("msg-assistant-001");
    }

    @Test
    void existingSessionNotRecreated() {
        when(sessionRepository.existsById("s1")).thenReturn(true);

        service.archiveTurn("s1", "tenant-a", "user-1", "问题", "回答", null, null, null);

        verify(sessionRepository, never()).save(any(KbSession.class));
        verify(messageRepository, Mockito.times(2)).save(any(KbMessage.class));
    }

    @Test
    void concurrentFirstTurnPrimaryKeyConflictTolerated() {
        when(sessionRepository.existsById("s1")).thenReturn(false);
        when(sessionRepository.save(any(KbSession.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"));

        // 主键冲突只影响会话创建，消息归档照常
        assertThatCode(() -> service.archiveTurn("s1", "t", "u", "问题", "回答", null, null, null))
            .doesNotThrowAnyException();
        verify(messageRepository, Mockito.times(2)).save(any(KbMessage.class));
    }

    @Test
    void titleTruncatedForLongFirstQuery() {
        when(sessionRepository.existsById(anyString())).thenReturn(false);
        String longQuery = "这是一个非常长的首问".repeat(20);

        service.archiveTurn("s2", "t", "u", longQuery, "回答", null, null, null);

        ArgumentCaptor<KbSession> captor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTitle()).hasSize(51).endsWith("…");
    }

    @Test
    void nullIdentityFallsBackToPlaceholder() {
        when(sessionRepository.existsById(anyString())).thenReturn(false);

        service.archiveTurn("s3", null, null, "问题", "回答", null, null, null);

        ArgumentCaptor<KbSession> captor = ArgumentCaptor.forClass(KbSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("unknown");
        assertThat(captor.getValue().getUserId()).isEqualTo("unknown");
    }

    @Test
    void repositoryFailureDoesNotPropagate() {
        when(sessionRepository.existsById(anyString())).thenReturn(true);
        when(messageRepository.save(any(KbMessage.class))).thenThrow(new RuntimeException("PG 连接失败"));

        assertThatCode(() -> service.archiveTurn("s1", "t", "u", "问题", "回答", null, null, null))
            .doesNotThrowAnyException();
        verify(sessionRepository, never()).incrementMessageCount(anyString(), eq(2));
    }

    // ── 溯源载荷归档（v2.17）──

    @Test
    void traceEventArchivedAsCitationsAndTraceIdAsMetadata() {
        when(sessionRepository.existsById("s1")).thenReturn(true);
        AgentStreamEvent.TraceEvent trace = sampleTrace();

        service.archiveTurn("s1", "t", "u", "问题", "回答", "msg-1", trace, "trace-xyz");

        ArgumentCaptor<KbMessage> captor = ArgumentCaptor.forClass(KbMessage.class);
        verify(messageRepository, Mockito.times(2)).save(captor.capture());
        List<KbMessage> messages = captor.getAllValues();
        // user 消息不带溯源
        assertThat(messages.get(0).getCitations()).isNull();
        // assistant 消息 citations 为 TRACE 同形 JSON，metadata 含 traceId
        assertThat(messages.get(1).getCitations())
            .contains("\"sources\"").contains("\"final\"").contains("chunk-1");
        assertThat(messages.get(1).getMetadata()).contains("trace-xyz");
    }

    @Test
    void nullTraceArchivesNullCitations() {
        when(sessionRepository.existsById("s1")).thenReturn(true);

        service.archiveTurn("s1", "t", "u", "问题", "回答", "msg-1", null, null);

        ArgumentCaptor<KbMessage> captor = ArgumentCaptor.forClass(KbMessage.class);
        verify(messageRepository, Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getCitations()).isNull();
        assertThat(captor.getAllValues().get(1).getMetadata()).isNull();
    }

    // ── 记忆回填（v2.17）──

    @Test
    void reseedRestoresHistoryWhenMemoryEmpty() {
        stubGuardAcquired();
        when(agentChatMemory.get("s1")).thenReturn(List.of());
        // PG 倒序返回（新→旧）：期望反转为升序写入
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc("s1"))
            .thenReturn(List.of(message("s1", "ASSISTANT", "回答一"), message("s1", "USER", "问题一")));

        service.reseedMemoryIfAbsent("s1");

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentChatMemory).add(eq("s1"), captor.capture());
        List<Message> reseeded = captor.getValue();
        assertThat(reseeded).hasSize(2);
        assertThat(reseeded.get(0)).isInstanceOf(UserMessage.class);
        assertThat(reseeded.get(0).getText()).isEqualTo("问题一");
        assertThat(reseeded.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(reseeded.get(1).getText()).isEqualTo("回答一");
    }

    @Test
    void reseedSkippedWhenMemoryPresent() {
        stubGuardAcquired();
        when(agentChatMemory.get("s1")).thenReturn(List.of(new UserMessage("还热着")));

        service.reseedMemoryIfAbsent("s1");

        verifyNoInteractions(messageRepository);
        verify(agentChatMemory, never()).add(anyString(), ArgumentMatchers.<List<Message>>any());
    }

    @Test
    void reseedSkippedWhenNoHistoryInPg() {
        stubGuardAcquired();
        when(agentChatMemory.get("s1")).thenReturn(List.of());
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc("s1")).thenReturn(List.of());

        service.reseedMemoryIfAbsent("s1");

        verify(agentChatMemory, never()).add(anyString(), ArgumentMatchers.<List<Message>>any());
    }

    @Test
    void reseedBlockedByConcurrentGuard() {
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        service.reseedMemoryIfAbsent("s1");

        verifyNoInteractions(agentChatMemory);
        verifyNoInteractions(messageRepository);
    }

    @Test
    void reseedFailureDoesNotPropagate() {
        stubGuardAcquired();
        when(agentChatMemory.get("s1")).thenThrow(new RuntimeException("Redis 抖动"));

        assertThatCode(() -> service.reseedMemoryIfAbsent("s1")).doesNotThrowAnyException();
        verifyNoInteractions(messageRepository);
    }

    @Test
    void reseedSkipsBlankContentMessages() {
        stubGuardAcquired();
        when(agentChatMemory.get("s1")).thenReturn(List.of());
        when(messageRepository.findTop20BySessionIdOrderByCreatedAtDesc("s1"))
            .thenReturn(List.of(message("s1", "USER", "问题一"), message("s1", "ASSISTANT", "  ")));

        service.reseedMemoryIfAbsent("s1");

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(agentChatMemory).add(eq("s1"), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getMessageType()).isEqualTo(MessageType.USER);
    }

    // ── 会话列表（3.15）──

    @Test
    void listSessionsClampsPageAndSize() {
        KbSession session = session("s1", "标题", 4);
        when(sessionRepository.findByTenantIdAndUserIdOrderByUpdatedAtDesc(
            eq("t"), eq("u"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(session)));

        List<SessionItem> items = service.listSessions("t", "u", -3, 9999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(sessionRepository).findByTenantIdAndUserIdOrderByUpdatedAtDesc(eq("t"), eq("u"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(PageRequest.of(0, 100));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo("s1");
        assertThat(items.get(0).title()).isEqualTo("标题");
        assertThat(items.get(0).messageCount()).isEqualTo(4);
    }

    // ── 历史消息（3.15）──

    @Test
    void loadMessagesRejectsUnknownSession() {
        when(sessionRepository.findById("s-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadMessages("s-x", "t", "u"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void loadMessagesRejectsCrossTenantAccess() {
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session("s1", "标题", 2)));

        assertThatThrownBy(() -> service.loadMessages("s1", "other-tenant", "u"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("SESSION_NOT_FOUND");
        assertThatThrownBy(() -> service.loadMessages("s1", "t", "other-user"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("SESSION_NOT_FOUND");
    }

    @Test
    void loadMessagesRestoresSourcesTraceIdAndFeedback() throws Exception {
        KbSession owned = session("s1", "标题", 2);
        owned.setTenantId("t");
        owned.setUserId("u");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(owned));

        KbMessage user = message("s1", "USER", "问题一");
        user.setId("m-user");
        KbMessage assistant = message("s1", "ASSISTANT", "回答一");
        assistant.setId("m-ast");
        assistant.setCitations(jsonMapper.writeValueAsString(sampleTrace()));
        assistant.setMetadata(jsonMapper.writeValueAsString(Map.of("traceId", "trace-xyz")));
        when(messageRepository.findBySessionIdOrderByCreatedAt("s1")).thenReturn(List.of(user, assistant));

        KbFeedback feedback = new KbFeedback();
        feedback.setMessageId("m-ast");
        feedback.setRating(FeedbackRating.POSITIVE);
        when(feedbackRepository.findByMessageIdInAndUserId(List.of("m-ast"), "u"))
            .thenReturn(List.of(feedback));

        List<HistoryMessageItem> items = service.loadMessages("s1", "t", "u");

        assertThat(items).hasSize(2);
        HistoryMessageItem userItem = items.get(0);
        assertThat(userItem.sources()).isNull();
        assertThat(userItem.feedback()).isNull();
        HistoryMessageItem assistantItem = items.get(1);
        assertThat(assistantItem.sources()).hasSize(1);
        assertThat(assistantItem.sources().get(0).source()).isEqualTo("final");
        assertThat(assistantItem.sources().get(0).chunks().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(assistantItem.traceId()).isEqualTo("trace-xyz");
        assertThat(assistantItem.feedback()).isEqualTo("POSITIVE");
    }

    @Test
    void loadMessagesCorruptedCitationsDegradesToNull() {
        KbSession owned = session("s1", "标题", 2);
        owned.setTenantId("t");
        owned.setUserId("u");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(owned));
        KbMessage assistant = message("s1", "ASSISTANT", "回答一");
        assistant.setId("m-ast");
        assistant.setCitations("{这不是合法 JSON");
        when(messageRepository.findBySessionIdOrderByCreatedAt("s1")).thenReturn(List.of(assistant));
        when(feedbackRepository.findByMessageIdInAndUserId(any(), eq("u"))).thenReturn(List.of());

        List<HistoryMessageItem> items = service.loadMessages("s1", "t", "u");

        assertThat(items.get(0).sources()).isNull();
    }

    // ── 删除会话（3.15）──

    @Test
    void deleteSessionRejectsCrossTenantAccess() {
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session("s1", "标题", 2)));

        assertThatThrownBy(() -> service.deleteSession("s1", "other-tenant", "u"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("SESSION_NOT_FOUND");
        verify(sessionRepository, never()).deleteById(anyString());
        verify(feedbackRepository, never()).deleteBySessionId(anyString());
    }

    @Test
    void deleteSessionDeletesFeedbackThenSessionAndClearsMemory() {
        KbSession owned = session("s1", "标题", 2);
        owned.setTenantId("t");
        owned.setUserId("u");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(owned));

        service.deleteSession("s1", "t", "u");

        // 反馈外键无级联：必须先清反馈再删会话，顺序不可颠倒（v2.17.1）
        var inOrder = Mockito.inOrder(feedbackRepository, sessionRepository);
        inOrder.verify(feedbackRepository).deleteBySessionId("s1");
        inOrder.verify(sessionRepository).deleteById("s1");
        verify(agentChatMemory).clear("s1");
    }

    @Test
    void deleteSessionSucceedsEvenIfMemoryClearFails() {
        KbSession owned = session("s1", "标题", 2);
        owned.setTenantId("t");
        owned.setUserId("u");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(owned));
        Mockito.doThrow(new RuntimeException("Redis 抖动")).when(agentChatMemory).clear("s1");

        assertThatCode(() -> service.deleteSession("s1", "t", "u")).doesNotThrowAnyException();
        verify(feedbackRepository).deleteBySessionId("s1");
        verify(sessionRepository).deleteById("s1");
    }

    // ── 测试工厂 ──

    private static AgentStreamEvent.TraceEvent sampleTrace() {
        return new AgentStreamEvent.TraceEvent(List.of(new AgentStreamEvent.SourceTrace(
            "final",
            List.of(new AgentStreamEvent.ChunkTrace(
                "chunk-1", "doc-1", "ddd.pdf", 4, Map.of("rerank_score", 0.92), "限界上下文……")),
            12L)));
    }

    private static KbMessage message(String sessionId, String role, String content) {
        KbMessage m = new KbMessage();
        m.setId(java.util.UUID.randomUUID().toString());
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    /** 默认会话工厂：tenant=t / user=u（跨租户用例直接改字段） */
    private static KbSession session(String id, String title, int messageCount) {
        KbSession s = new KbSession();
        s.setId(id);
        s.setTenantId("t");
        s.setUserId("u");
        s.setTitle(title);
        s.setMessageCount(messageCount);
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }

    @SuppressWarnings("unchecked")
    private void stubGuardAcquired() {
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
        when(bucket.trySet(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }
}
