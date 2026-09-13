package com.enterprise.kb.ai.agent.dingtalk;

import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DingTalkChatService 测试（Phase5簇⑥ 5.12）：编排语义（提取/限流/审计/异步提交）、
 * 溯源附录（final trace [ref-N] 精确映射）、超时与异常话术、会话前缀与身份透传。
 */
class DingTalkChatServiceTest {

    private static final String WEBHOOK = "https://oapi.dingtalk.com/robot/sendBySession";

    private RagChatService ragChatService;
    private DingTalkRateLimiter rateLimiter;
    private DingTalkAuditRecorder auditRecorder;
    private DingTalkReplyClient replyClient;
    private SimpleMeterRegistry meterRegistry;
    private DingTalkProperties properties;
    private ExecutorService executor;
    private DingTalkChatService service;

    @BeforeEach
    void setUp() {
        ragChatService = mock(RagChatService.class);
        rateLimiter = mock(DingTalkRateLimiter.class);
        auditRecorder = mock(DingTalkAuditRecorder.class);
        replyClient = mock(DingTalkReplyClient.class);
        meterRegistry = new SimpleMeterRegistry();
        properties = new DingTalkProperties();
        properties.setTenantId("tenant-a");
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service = new DingTalkChatService(ragChatService, rateLimiter, auditRecorder,
            replyClient, new AiBusinessMetrics(meterRegistry), properties, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    private static ChatbotMessage message(String text, String conversationId) {
        ChatbotMessage message = new ChatbotMessage();
        MessageContent content = new MessageContent();
        content.setContent(text);
        message.setText(content);
        message.setConversationId(conversationId);
        message.setSessionWebhook(WEBHOOK);
        message.setSenderStaffId("staff-1");
        return message;
    }

    /** chatRag 桩：捕获 ctx 并在其上落 final trace 快照（模拟流末可读溯源） */
    private void stubChatRagWithFinalTrace(String answer, String fileName, int pageNum) {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenAnswer(inv -> {
            RetrievalContext ctx = inv.getArgument(2);
            Document doc = Document.builder().text("证据内容")
                .metadata(Map.of(
                    Constants.Retrieval.META_FILE_NAME, fileName,
                    Constants.Retrieval.META_PAGE_NUM, pageNum,
                    Constants.Retrieval.META_CHUNK_ID, "chunk-abcdef123456"))
                .build();
            ctx.addTraceEntry(Constants.Retrieval.TRACE_SOURCE_FINAL, List.of(doc), 12L);
            return answer;
        });
    }

    @Test
    void happyPathRepliesWithSourceAppendix() {
        stubChatRagWithFinalTrace("答案正文，见 [ref-1]。", "员工手册.pdf", 3);

        service.handle(message("年假政策是什么", "cid-1"));

        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK), eq("年假政策是什么"), markdown.capture());
        // 溯源附录：[ref-N] 与 final trace 序列对齐 + 文件名/页码/chunk 短码
        assertThat(markdown.getValue())
            .startsWith("答案正文，见 [ref-1]。")
            .contains("**溯源**")
            .contains("[ref-1] 员工手册.pdf（p.3）")
            .contains("`chunk-ab`");
        // 会话前缀与身份透传
        ArgumentCaptor<RetrievalContext> ctx = ArgumentCaptor.forClass(RetrievalContext.class);
        verify(ragChatService).chatRag(eq("年假政策是什么"), eq("dingtalk-cid-1"), ctx.capture());
        assertThat(ctx.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(ctx.getValue().getUserId()).isEqualTo("staff-1");
        // 指标：message 计数在入口线程即发生，replied/duration 在流末
        assertThat(meterRegistry.counter("rag.dingtalk.message").count()).isEqualTo(1.0);
        verify(replyClient, timeout(3000).times(1)).replyMarkdown(anyString(), anyString(), anyString());
        assertThat(meterRegistry.counter("rag.dingtalk.replied").count()).isEqualTo(1.0);
        verify(auditRecorder).record(anyString(), any(RetrievalContext.class));
    }

    @Test
    void noFinalTraceOmitsAppendix() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("直答（空证据拒答路径）");

        service.handle(message("你好", "cid-2"));

        ArgumentCaptor<String> markdown = ArgumentCaptor.forClass(String.class);
        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK), anyString(), markdown.capture());
        assertThat(markdown.getValue()).doesNotContain("溯源");
    }

    @Test
    void nonTextMessageIgnored() {
        ChatbotMessage noText = message("x", "cid-3");
        noText.setText(null);

        service.handle(noText);

        // 非文本消息零触达：不计数/不回复/不进对话
        assertThat(meterRegistry.counter("rag.dingtalk.message").count()).isZero();
        verify(ragChatService, never()).chatRag(anyString(), anyString(), any());
        verify(replyClient, never()).replyMarkdown(anyString(), anyString(), anyString());
    }

    @Test
    void missingWebhookDropped() {
        ChatbotMessage noHook = message("问题", "cid-4");
        noHook.setSessionWebhook(null);

        service.handle(noHook);

        verify(replyClient, never()).replyMarkdown(anyString(), anyString(), anyString());
    }

    @Test
    void anonymousUserFallback() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("答案");
        ChatbotMessage anonymous = message("问题", "cid-5");
        anonymous.setSenderStaffId(null);

        service.handle(anonymous);

        ArgumentCaptor<RetrievalContext> ctx = ArgumentCaptor.forClass(RetrievalContext.class);
        verify(ragChatService, timeout(3000)).chatRag(anyString(), anyString(), ctx.capture());
        assertThat(ctx.getValue().getUserId()).isEqualTo("dingtalk-anonymous");
    }

    @Test
    void rateLimitedRepliesNoticeWithoutChat() {
        org.mockito.Mockito.doThrow(new BusinessException(Constants.ErrorCodes.RATE_LIMITED, "限流"))
            .when(rateLimiter).acquire("tenant-a");

        service.handle(message("问题", "cid-6"));

        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK), eq("提示"), eq("请求过于频繁，请稍后再试。"));
        verify(ragChatService, never()).chatRag(anyString(), anyString(), any());
    }

    @Test
    void chatTimeoutRepliesNoticeWithoutInterruption() throws Exception {
        properties.setChatTimeoutSeconds(1);
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenAnswer(inv -> {
            TimeUnit.MILLISECONDS.sleep(2500); // 后台慢对话——超时放弃等待但不中断（非打断式）
            return "迟到答案";
        });

        service.handle(message("慢问题", "cid-7"));

        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK), anyString(), eq("回答生成超时，请稍后重试。"));
        assertThat(meterRegistry.counter("rag.dingtalk.error").count()).isEqualTo(1.0);
        // 迟到结果丢弃：超时话术后不再发第二条回复
        TimeUnit.MILLISECONDS.sleep(500);
        verify(replyClient, timeout(100).times(1)).replyMarkdown(anyString(), anyString(), anyString());
    }

    @Test
    void chatFailureRepliesFallbackNotice() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenThrow(new RuntimeException("模型不可用"));

        service.handle(message("问题", "cid-8"));

        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK), anyString(), eq("服务暂时不可用，请稍后重试。"));
        assertThat(meterRegistry.counter("rag.dingtalk.error").count()).isEqualTo(1.0);
    }

    @Test
    void replyFailureCountedOnly() {
        stubChatRagWithFinalTrace("答案", "手册.pdf", 1);
        org.mockito.Mockito.doThrow(new IllegalStateException("webhook 过期"))
            .when(replyClient).replyMarkdown(anyString(), anyString(), anyString());

        service.handle(message("问题", "cid-9"));

        // 回复失败不击穿（消息已处理不可重放）——error 计数：回复失败 +1
        verify(replyClient, timeout(3000)).replyMarkdown(anyString(), anyString(), anyString());
        assertThat(meterRegistry.counter("rag.dingtalk.error").count()).isEqualTo(1.0);
    }

    @Test
    void longTitleTruncated() {
        when(ragChatService.chatRag(anyString(), anyString(), any())).thenReturn("答案");
        String longQuestion = "这是一个非常非常长的问题标题超过二十个字符的情况用于验证截断逻辑";

        service.handle(message(longQuestion, "cid-10"));

        verify(replyClient, timeout(3000)).replyMarkdown(eq(WEBHOOK),
            eq("这是一个非常非常长的问题标题超过二十个字…"), anyString());
    }
}
