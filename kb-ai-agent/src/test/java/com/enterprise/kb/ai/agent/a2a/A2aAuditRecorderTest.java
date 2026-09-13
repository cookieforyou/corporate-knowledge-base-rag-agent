package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2aAuditRecorder 测试（Phase5簇⑥ 批2，DingTalkAuditRecorderTest 同构）：
 * 默认关零落库、开则轻行（mode=a2a + traceId 复用当轮 ctx）、脱敏与旁路容错。
 */
class A2aAuditRecorderTest {

    private KbAuditLogRepository repository;
    private PiiRecognizerRegistry piiRegistry;
    private A2aAuditRecorder recorder;

    @BeforeEach
    void setUp() {
        repository = mock(KbAuditLogRepository.class);
        piiRegistry = mock(PiiRecognizerRegistry.class);
        when(piiRegistry.mask(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private A2aAuditRecorder recorderWithDbAudit(boolean enabled) {
        A2aProperties properties = new A2aProperties();
        properties.getAudit().setEnabled(enabled);
        // 异步执行器测试内联化：提交即执行（McpAuditRecorderTest 同款）
        AsyncTaskExecutor inlineExecutor = mock(AsyncTaskExecutor.class);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(inlineExecutor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        return new A2aAuditRecorder(repository, inlineExecutor,
            JsonMapper.builder().build(), piiRegistry, properties);
    }

    @Test
    void defaultOffSkipsDb() {
        recorder = recorderWithDbAudit(false);

        recorder.record("问题", context());

        // 默认关：结构化日志恒开即足，零 DB 写入（MCP B3 同款观察纪律）
        verify(repository, never()).save(any());
    }

    @Test
    void dbAuditWritesLightRowWithTraceIdReused() {
        recorder = recorderWithDbAudit(true);
        RetrievalContext ctx = context();
        ctx.setTraceId("trace-123");

        recorder.record("问题", ctx);

        ArgumentCaptor<KbAuditLog> captor = ArgumentCaptor.forClass(KbAuditLog.class);
        verify(repository).save(captor.capture());
        KbAuditLog row = captor.getValue();
        assertThat(row.getMode()).isEqualTo("a2a");
        // 入口轻行与对话链审计行凭当轮同一 traceId 关联
        assertThat(row.getTraceId()).isEqualTo("trace-123");
        assertThat(row.getTenantId()).isEqualTo("tenant-a");
        assertThat(row.getUserId()).isEqualTo("user-1");
        assertThat(row.getQueryText()).isEqualTo("问题");
        assertThat(row.getToolCalls()).contains("\"tool\":\"chat\"");
        assertThat(row.getStatus()).isEqualTo(Constants.AuditStatus.SUCCESS);
    }

    @Test
    void persistFailureNeverPropagates() {
        recorder = recorderWithDbAudit(true);
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        // 旁路数据：落库失败只丢弃，绝不击穿请求处理
        recorder.record("问题", context());
        verify(repository).save(any());
    }

    @Test
    void piiMaskedBeforePersist() {
        recorder = recorderWithDbAudit(true);
        when(piiRegistry.mask("含联系方式的问题")).thenReturn("含***的问题");

        recorder.record("含联系方式的问题", context());

        ArgumentCaptor<KbAuditLog> captor = ArgumentCaptor.forClass(KbAuditLog.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getQueryText()).isEqualTo("含***的问题");
    }

    private static RetrievalContext context() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ctx.setUserId("user-1");
        return ctx;
    }
}
