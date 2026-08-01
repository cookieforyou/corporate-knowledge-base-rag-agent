package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RetrievalTraceAdvisor 单测（2.11）：身份填充 + trace 旁路 + 非 Web 降级
 */
class RetrievalTraceAdvisorTest {

    @AfterEach
    void resetRequestScope() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RetrievalTraceAdvisor advisor(RetrievalContext ctx, RequestIdentityResolver identity) {
        @SuppressWarnings("unchecked")
        ObjectProvider<RetrievalContext> ctxProvider = Mockito.mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestIdentityResolver> idProvider = Mockito.mock(ObjectProvider.class);
        if (ctx != null) Mockito.when(ctxProvider.getObject()).thenReturn(ctx);
        Mockito.when(idProvider.getIfAvailable()).thenReturn(identity);
        return new RetrievalTraceAdvisor(ctxProvider, idProvider);
    }

    @Test
    void before_fillsTenantAndUser_fromIdentityResolver() {
        RequestContextHolder.setRequestAttributes(Mockito.mock(RequestAttributes.class));
        RetrievalContext ctx = new RetrievalContext();
        RequestIdentityResolver identity = new RequestIdentityResolver() {
            public String getTenantId() { return "tenant_002"; }
            public String getUserId() { return "user-uuid-1"; }
        };

        ChatClientRequest out = advisor(ctx, identity)
            .before(new ChatClientRequest(new Prompt("q"), new HashMap<>()), null);

        assertEquals("tenant_002", ctx.getTenantId());
        assertEquals("user-uuid-1", ctx.getUserId());
        // 租户过滤表达式随 tenantId 生效（tenant_id + is_deleted 双条件）
        assertNotNull(ctx.getSecurityFilter());
        // 起始时刻透传到请求上下文
        assertNotNull(out.context().get("trace_start_ms"));
    }

    @Test
    void before_noIdentityResolver_skipsFilling() {
        RequestContextHolder.setRequestAttributes(Mockito.mock(RequestAttributes.class));
        RetrievalContext ctx = new RetrievalContext();

        advisor(ctx, null).before(new ChatClientRequest(new Prompt("q"), Map.of()), null);

        assertNull(ctx.getTenantId());
    }

    @Test
    void before_nonWebContext_degradesToPassthrough() {
        // 无 RequestAttributes（kb-eval 形态）：不触碰作用域 Bean，请求原样放行
        RetrievalContext ctx = new RetrievalContext();
        ChatClientRequest request = new ChatClientRequest(new Prompt("q"), Map.of());

        ChatClientRequest out = advisor(ctx, new RequestIdentityResolver() {
            public String getTenantId() { throw new AssertionError("非 Web 上下文不应解析身份"); }
            public String getUserId() { throw new AssertionError("非 Web 上下文不应解析身份"); }
        }).before(request, null);

        assertNull(ctx.getTenantId());
        assertFalse(out.context().containsKey("trace_start_ms"));
    }

    @Test
    void after_putsTraceSummaryIntoResponseContext() {
        RequestContextHolder.setRequestAttributes(Mockito.mock(RequestAttributes.class));
        RetrievalContext ctx = new RetrievalContext();
        ctx.addTraceEntry("bm25", List.of());

        ChatClientResponse out = advisor(ctx, null)
            .after(ChatClientResponse.builder().context(Map.of()).build(), null);

        assertNotNull(out.context().get("rag_trace"));
        assertEquals(1, out.context().get("retrieval_count"));
        assertEquals(0.0, out.context().get("top_fusion_score"));
    }

    @Test
    void order_is450_beforeRetrievalAugmentationAdvisor() {
        assertEquals(450, advisor(null, null).getOrder());
    }
}
