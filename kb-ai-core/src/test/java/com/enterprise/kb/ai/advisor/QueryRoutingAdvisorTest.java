package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 意图分类 Advisor 测试（5.4 收窄版）——规则快路 / LLM 分类两分支 / fail-open / 开关
 */
class QueryRoutingAdvisorTest {

    private ChatClient chatClient;
    private ChatMemory chatMemory;
    private SimpleMeterRegistry registry;
    private AiBusinessMetrics metrics;
    private final AdvisorChain chain = mock(AdvisorChain.class);

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        chatMemory = mock(ChatMemory.class);
        registry = new SimpleMeterRegistry();
        metrics = new AiBusinessMetrics(registry);
    }

    private QueryRoutingAdvisor advisor(boolean enabled) {
        return new QueryRoutingAdvisor(
            builderOf(chatClient), chatMemory, metrics, enabled, 6);
    }

    private static ChatClient.Builder builderOf(ChatClient chatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        return builder;
    }

    private ChatClientRequest request(String userText, RetrievalContext ctx) {
        Map<String, Object> context = new HashMap<>();
        if (ctx != null) {
            context.put(RetrievalContext.CONTEXT_KEY, ctx);
        }
        context.put(ChatMemory.CONVERSATION_ID, "sess-1");
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage(userText))), context);
    }

    private void stubClassify(IntentResult result) {
        when(chatClient.prompt().user(anyString()).call()
            .entity(eq(IntentResult.class))).thenReturn(result);
    }

    // ── L1 规则快路 ──

    @Test
    void ruleFastPathChineseGreetingSkipsRetrievalWithoutLlm() {
        RetrievalContext ctx = new RetrievalContext();

        advisor(true).before(request("你好", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isTrue();
        assertThat(registry.counter("rag.routing.chitchat").count()).isEqualTo(1.0);
        verifyNoInteractions(chatClient);
    }

    @Test
    void ruleFastPathCaseInsensitiveEnglishGreeting() {
        RetrievalContext ctx = new RetrievalContext();

        advisor(true).before(request("Hello", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isTrue();
        verifyNoInteractions(chatClient);
    }

    @Test
    void ruleFastPathThanksSkips() {
        RetrievalContext ctx = new RetrievalContext();

        advisor(true).before(request("谢谢", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isTrue();
    }

    @Test
    void longMixedGreetingFallsThroughToLlm() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(new IntentResult("KNOWLEDGE", "年假政策"));

        advisor(true).before(request("你好，请问公司的年假政策是怎样的？", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        assertThat(ctx.getRewrittenQuery()).isEqualTo("年假政策");
    }

    // ── L2 LLM 分类 ──

    @Test
    void llmKnowledgeWritesPreRewrittenQuery() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(new IntentResult("KNOWLEDGE", "增值税发票的税率是多少"));

        advisor(true).before(request("它的税率是多少", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        assertThat(ctx.getRewrittenQuery()).isEqualTo("增值税发票的税率是多少");
        assertThat(registry.counter("rag.routing.knowledge").count()).isEqualTo(1.0);
    }

    @Test
    void llmChitchatSetsSkip() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(new IntentResult("CHITCHAT", null));

        advisor(true).before(request("我刚才问了什么", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isTrue();
        assertThat(registry.counter("rag.routing.chitchat").count()).isEqualTo(1.0);
    }

    @Test
    void intentComparisonIsCaseInsensitive() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(new IntentResult("chitchat", null));

        advisor(true).before(request("我上一个问题是什么来着", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isTrue();
    }

    @Test
    void knowledgeWithBlankRewriteFallsBackToOriginalQuery() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(new IntentResult("KNOWLEDGE", "  "));

        advisor(true).before(request("增值税发票税率是多少", ctx), chain);

        assertThat(ctx.getRewrittenQuery()).isEqualTo("增值税发票税率是多少");
    }

    @Test
    void historyLoadedFromChatMemoryByConversationId() {
        RetrievalContext ctx = new RetrievalContext();
        List<Message> history = List.of(
            new UserMessage("什么是增值税发票？"),
            new AssistantMessage("增值税发票是……"));
        when(chatMemory.get("sess-1")).thenReturn(history);
        stubClassify(new IntentResult("KNOWLEDGE", "增值税发票的税率"));

        advisor(true).before(request("那它的税率呢", ctx), chain);

        verify(chatMemory).get("sess-1");
        assertThat(ctx.getRewrittenQuery()).isEqualTo("增值税发票的税率");
    }

    // ── fail-open ──

    @Test
    void nullClassificationResultFailsOpenToKnowledge() {
        RetrievalContext ctx = new RetrievalContext();
        stubClassify(null);

        advisor(true).before(request("随便一个问题", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        assertThat(ctx.getRewrittenQuery()).isEqualTo("随便一个问题");
        assertThat(registry.counter("rag.routing.knowledge").count()).isEqualTo(1.0);
    }

    @Test
    void classificationExceptionFailsOpenWithoutThrowing() {
        RetrievalContext ctx = new RetrievalContext();
        when(chatClient.prompt().user(anyString()).call()
            .entity(eq(IntentResult.class)))
            .thenThrow(new RuntimeException("LLM down"));

        advisor(true).before(request("任意问题", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        assertThat(registry.counter("rag.routing.knowledge").count()).isEqualTo(1.0);
    }

    // ── 开关与防御 ──

    @Test
    void disabledAdvisorPassesThroughWithoutClassification() {
        RetrievalContext ctx = new RetrievalContext();

        advisor(false).before(request("你好", ctx), chain);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        verifyNoInteractions(chatClient);
        assertThat(registry.counter("rag.routing.chitchat").count()).isZero();
    }

    @Test
    void missingRetrievalContextPassesThrough() {
        ChatClientRequest original = request("你好", null);

        ChatClientRequest result = advisor(true).before(original, chain);

        assertThat(result).isSameAs(original);
        verifyNoInteractions(chatClient);
    }
}
