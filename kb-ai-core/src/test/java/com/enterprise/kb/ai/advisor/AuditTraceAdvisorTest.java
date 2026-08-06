package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.core.task.AsyncTaskExecutor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 全链路审计 Advisor 测试（3.12）——成功/被拒/流式聚合/PII 脱敏/禁用直通
 */
class AuditTraceAdvisorTest {

    /** 同步执行落库的测试执行器，断言无需等待（Spring 7 SyncTaskExecutor 不再实现 AsyncTaskExecutor） */
    private static final AsyncTaskExecutor SYNC_EXECUTOR = new AsyncTaskExecutor() {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public CompletableFuture<Void> submitCompletable(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    };

    private KbAuditLogRepository repository;
    private CallAdvisorChain callChain;
    private StreamAdvisorChain streamChain;
    private AuditTraceAdvisor advisor;

    @BeforeEach
    void setUp() {
        repository = mock(KbAuditLogRepository.class);
        callChain = mock(CallAdvisorChain.class);
        streamChain = mock(StreamAdvisorChain.class);
        advisor = new AuditTraceAdvisor(repository, new ObjectMapper(), SYNC_EXECUTOR, true);
    }

    private static RetrievalContext ctxWithTrace() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ctx.setUserId("user-1");
        ctx.setRewrittenQuery("改写后的问题");
        ctx.addTraceEntry("bm25", List.of(
            new Document("命中内容", Map.of("chunk_id", "c-1", "file_name", "f.pdf", "fusion_score", 0.9))));
        ctx.addTraceEntry("final", List.of(
            new Document("命中内容", Map.of("chunk_id", "c-1", "file_name", "f.pdf", "rerank_score", 0.95))));
        return ctx;
    }

    private static ChatClientRequest request(RetrievalContext ctx, String mode, String query) {
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(query))),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx,
                AuditTraceAdvisor.MODE_KEY, mode,
                ChatMemory.CONVERSATION_ID, "s-audit-1"));
    }

    private static ChatClientResponse response(String text) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(10, 20))
            .build();
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata))
            .context(Map.of())
            .build();
    }

    private KbAuditLog captureSaved() {
        ArgumentCaptor<KbAuditLog> captor = ArgumentCaptor.forClass(KbAuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void syncSuccessRecordsFullAudit() {
        RetrievalContext ctx = ctxWithTrace();
        when(callChain.nextCall(any())).thenReturn(response("回答内容"));

        advisor.adviseCall(request(ctx, "rag", "什么是增值税发票？"), callChain);

        KbAuditLog audit = captureSaved();
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getMode()).isEqualTo("rag");
        assertThat(audit.getSessionId()).isEqualTo("s-audit-1");
        assertThat(audit.getTenantId()).isEqualTo("tenant-a");
        assertThat(audit.getUserId()).isEqualTo("user-1");
        assertThat(audit.getQueryText()).isEqualTo("什么是增值税发票？");
        assertThat(audit.getRewrittenQuery()).isEqualTo("改写后的问题");
        assertThat(audit.getRetrievalType()).isEqualTo("hybrid");
        assertThat(audit.getRetrievedChunks()).contains("c-1");
        assertThat(audit.getRerankedChunks()).contains("c-1");
        assertThat(audit.getFinalAnswer()).isEqualTo("回答内容");
        assertThat(audit.getTokenUsage()).contains("\"total_tokens\":30");
        assertThat(audit.getLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(audit.getTraceId()).isNotBlank();
    }

    @Test
    void queryTextSanitizedBeforePersist() {
        when(callChain.nextCall(any())).thenReturn(response("回答"));

        advisor.adviseCall(request(ctxWithTrace(), "rag", "我的手机号是 13911112222"), callChain);

        assertThat(captureSaved().getQueryText())
            .contains("1***-****-****")
            .doesNotContain("13911112222");
    }

    @Test
    void rejectedRequestRecordedAndErrorPropagated() {
        when(callChain.nextCall(any()))
            .thenThrow(new BusinessException("RATE_LIMITED", "请求过于频繁"));

        assertThatThrownBy(() -> advisor.adviseCall(request(ctxWithTrace(), "rag", "问题"), callChain))
            .isInstanceOf(BusinessException.class);

        KbAuditLog audit = captureSaved();
        assertThat(audit.getStatus()).isEqualTo("REJECTED");
        assertThat(audit.getErrorCode()).isEqualTo("RATE_LIMITED");
        assertThat(audit.getFinalAnswer()).isNull();
    }

    @Test
    void unexpectedErrorRecordedAsError() {
        when(callChain.nextCall(any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> advisor.adviseCall(request(ctxWithTrace(), "tool", "问题"), callChain))
            .isInstanceOf(IllegalStateException.class);

        KbAuditLog audit = captureSaved();
        assertThat(audit.getStatus()).isEqualTo("ERROR");
        assertThat(audit.getErrorCode()).isEqualTo("IllegalStateException");
        assertThat(audit.getMode()).isEqualTo("tool");
    }

    @Test
    void streamAggregatesAnswerAndRecordsOnce() {
        when(streamChain.nextStream(any())).thenReturn(
            Flux.just(response("增值"), response("税"), response("解答")));

        advisor.adviseStream(request(ctxWithTrace(), "rag", "问题"), streamChain)
            .collectList().block();

        assertThat(captureSaved().getFinalAnswer()).isEqualTo("增值税解答");
    }

    @Test
    void streamErrorRecordedAndPropagated() {
        when(streamChain.nextStream(any()))
            .thenReturn(Flux.error(new BusinessException("PROMPT_INJECTION", "注入拦截")));

        assertThatThrownBy(() -> advisor.adviseStream(request(ctxWithTrace(), "rag", "问题"), streamChain)
            .collectList().block())
            .isInstanceOf(BusinessException.class);

        KbAuditLog audit = captureSaved();
        assertThat(audit.getStatus()).isEqualTo("REJECTED");
        assertThat(audit.getErrorCode()).isEqualTo("PROMPT_INJECTION");
    }

    @Test
    void toolCallsRecordedAsJson() {
        RetrievalContext ctx = ctxWithTrace();
        ctx.addToolCall(new RetrievalContext.ToolCall(
            "submitLeaveRequest", "PENDING_APPROVAL", "apv-1", "摘要"));
        when(callChain.nextCall(any())).thenReturn(response("待确认"));

        advisor.adviseCall(request(ctx, "tool", "提交请假"), callChain);

        assertThat(captureSaved().getToolCalls()).contains("apv-1").contains("PENDING_APPROVAL");
    }

    @Test
    void disabledAdvisorPassesThroughWithoutPersist() {
        AuditTraceAdvisor disabled = new AuditTraceAdvisor(repository, new ObjectMapper(),
            SYNC_EXECUTOR, false);
        when(callChain.nextCall(any())).thenReturn(response("回答"));

        disabled.adviseCall(request(ctxWithTrace(), "rag", "问题"), callChain);

        verifyNoInteractions(repository);
    }
}
