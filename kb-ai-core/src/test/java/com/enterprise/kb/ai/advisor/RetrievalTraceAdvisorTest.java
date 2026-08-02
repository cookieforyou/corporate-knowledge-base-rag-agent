package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalTraceAdvisor 单测（2026-08-02 重构后形态）：
 * 纯上下文 Map 操作——起始时刻戳 + trace 快照写入响应上下文
 */
class RetrievalTraceAdvisorTest {

    private final RetrievalTraceAdvisor advisor = new RetrievalTraceAdvisor();

    @Test
    void before_stampsTraceStartIntoRequestContext() {
        ChatClientRequest out = advisor.before(
            new ChatClientRequest(new Prompt("q"), new HashMap<>()), null);

        assertNotNull(out.context().get("trace_start_ms"));
        // advisor 参数（检索上下文键）原样透传
        RetrievalContext ctx = new RetrievalContext();
        ChatClientRequest withParam = advisor.before(new ChatClientRequest(new Prompt("q"),
            new HashMap<>(Map.of(RetrievalContext.CONTEXT_KEY, ctx))), null);
        assertSame(ctx, withParam.context().get(RetrievalContext.CONTEXT_KEY));
    }

    @Test
    void after_writesTraceSummaryIntoResponseContext() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.addTraceEntry("vector", List.of());

        ChatClientResponse out = advisor.after(ChatClientResponse.builder()
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build(), null);

        assertNotNull(out.context().get("rag_trace"));
        assertEquals(1, out.context().get("retrieval_count"));
        assertEquals(0.0, out.context().get("top_fusion_score"));
    }

    @Test
    void after_withoutContext_passesThrough() {
        ChatClientResponse response = ChatClientResponse.builder().context(Map.of()).build();

        assertSame(response, advisor.after(response, null));
    }

    @Test
    void order_is450_beforeRetrievalAugmentationAdvisor() {
        assertEquals(450, advisor.getOrder());
    }
}
