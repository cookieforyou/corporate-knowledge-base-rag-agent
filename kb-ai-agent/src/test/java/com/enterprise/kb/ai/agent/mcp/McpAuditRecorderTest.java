package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * McpAuditRecorder 测试（安全簇② B3）：日志恒开、DB 轻行开关、旁路容错
 */
class McpAuditRecorderTest {

    private KbAuditLogRepository auditLogRepository;
    private AsyncTaskExecutor auditExecutor;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(KbAuditLogRepository.class);
        auditExecutor = mock(AsyncTaskExecutor.class);
        // 异步执行器测试内联化：提交即执行
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(auditExecutor).execute(any(Runnable.class));
    }

    private static RetrievalContext ctx() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("t-1");
        ctx.setUserId("u-1");
        return ctx;
    }

    @Test
    void dbAuditDisabledSkipsPersistence() {
        McpAuditRecorder recorder = new McpAuditRecorder(
            auditLogRepository, auditExecutor, new JsonMapper(), false);

        recorder.record("search", "检索问题", ctx());

        verifyNoInteractions(auditLogRepository);
        verifyNoInteractions(auditExecutor);
    }

    @Test
    void dbAuditEnabledPersistsLightRow() {
        McpAuditRecorder recorder = new McpAuditRecorder(
            auditLogRepository, auditExecutor, new JsonMapper(), true);

        recorder.record("get_document", "doc-123", ctx());

        ArgumentCaptor<KbAuditLog> captor = ArgumentCaptor.forClass(KbAuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        KbAuditLog audit = captor.getValue();
        assertThat(audit.getMode()).isEqualTo("mcp");
        assertThat(audit.getTenantId()).isEqualTo("t-1");
        assertThat(audit.getUserId()).isEqualTo("u-1");
        assertThat(audit.getQueryText()).isEqualTo("doc-123");
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getTraceId()).hasSize(36);
        assertThat(audit.getToolCalls()).contains("get_document");
        // 轻行形态：无检索快照/token/会话字段
        assertThat(audit.getSessionId()).isNull();
        assertThat(audit.getRetrievedChunks()).isNull();
        assertThat(audit.getTokenUsage()).isNull();
    }

    @Test
    void persistenceFailureSwallowed() {
        doThrow(new RuntimeException("PG 抖动")).when(auditLogRepository).save(any());
        McpAuditRecorder recorder = new McpAuditRecorder(
            auditLogRepository, auditExecutor, new JsonMapper(), true);

        // 旁路数据哲学：落库失败绝不击穿工具调用
        assertThatCode(() -> recorder.record("search", "检索问题", ctx()))
            .doesNotThrowAnyException();
    }
}
