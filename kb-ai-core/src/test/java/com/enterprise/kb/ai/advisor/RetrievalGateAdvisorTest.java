package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 检索门控 Advisor 测试（5.4 收窄版）——skip/非 skip × call/stream 四象限 + 无上下文 fail-open
 */
class RetrievalGateAdvisorTest {

    private RetrievalAugmentationAdvisor delegate;
    private RetrievalGateAdvisor gate;
    private CallAdvisorChain callChain;
    private StreamAdvisorChain streamChain;

    @BeforeEach
    void setUp() {
        delegate = mock(RetrievalAugmentationAdvisor.class);
        gate = new RetrievalGateAdvisor(delegate);
        callChain = mock(CallAdvisorChain.class);
        streamChain = mock(StreamAdvisorChain.class);
    }

    private ChatClientRequest request(RetrievalContext ctx) {
        Map<String, Object> context = new HashMap<>();
        if (ctx != null) {
            context.put(RetrievalContext.CONTEXT_KEY, ctx);
        }
        return new ChatClientRequest(
            new Prompt(List.of(new UserMessage("问题"))), context);
    }

    @Test
    void skipCallPathBypassesDelegate() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setSkipRetrieval(true);
        ChatClientRequest req = request(ctx);
        ChatClientResponse expected = mock(ChatClientResponse.class);
        when(callChain.nextCall(req)).thenReturn(expected);

        assertThat(gate.adviseCall(req, callChain)).isSameAs(expected);
        verifyNoInteractions(delegate);
    }

    @Test
    void skipStreamPathBypassesDelegate() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setSkipRetrieval(true);
        ChatClientRequest req = request(ctx);
        ChatClientResponse expected = mock(ChatClientResponse.class);
        when(streamChain.nextStream(req)).thenReturn(Flux.just(expected));

        assertThat(gate.adviseStream(req, streamChain)
            .collectList().block()).containsExactly(expected);
        verifyNoInteractions(delegate);
    }

    @Test
    void noSkipCallPathDelegates() {
        RetrievalContext ctx = new RetrievalContext();
        ChatClientRequest req = request(ctx);
        ChatClientResponse expected = mock(ChatClientResponse.class);
        when(delegate.adviseCall(req, callChain)).thenReturn(expected);

        assertThat(gate.adviseCall(req, callChain)).isSameAs(expected);
        verify(delegate).adviseCall(req, callChain);
    }

    @Test
    void noSkipStreamPathDelegates() {
        RetrievalContext ctx = new RetrievalContext();
        ChatClientRequest req = request(ctx);
        ChatClientResponse expected = mock(ChatClientResponse.class);
        when(delegate.adviseStream(req, streamChain)).thenReturn(Flux.just(expected));

        assertThat(gate.adviseStream(req, streamChain)
            .collectList().block()).containsExactly(expected);
        verify(delegate).adviseStream(req, streamChain);
    }

    @Test
    void missingContextDelegatesFailOpen() {
        ChatClientRequest req = request(null);
        ChatClientResponse expected = mock(ChatClientResponse.class);
        when(delegate.adviseCall(req, callChain)).thenReturn(expected);

        assertThat(gate.adviseCall(req, callChain)).isSameAs(expected);
        verify(delegate).adviseCall(req, callChain);
    }
}
