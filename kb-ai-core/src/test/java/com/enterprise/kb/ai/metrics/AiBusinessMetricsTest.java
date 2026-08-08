package com.enterprise.kb.ai.metrics;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 业务指标测试（3.13）——各指标注册与记录语义
 */
class AiBusinessMetricsTest {

    private SimpleMeterRegistry registry;
    private AiBusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiBusinessMetrics(registry);
    }

    @Test
    void feedbackCountersSplitBySentiment() {
        metrics.recordFeedback(true);
        metrics.recordFeedback(false);
        metrics.recordFeedback(false);

        assertThat(registry.counter("rag.feedback.like").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.feedback.dislike").count()).isEqualTo(2.0);
    }

    @Test
    void retrievalHitRateCounters() {
        metrics.recordRetrieval(true);
        metrics.recordRetrieval(true);
        metrics.recordRetrieval(false);

        assertThat(registry.counter("rag.retrieval.total").count()).isEqualTo(3.0);
        assertThat(registry.counter("rag.retrieval.hit").count()).isEqualTo(2.0);
    }

    @Test
    void retrievalLatencyTimerRecords() {
        metrics.recordRetrievalLatency(Duration.ofMillis(120));

        assertThat(registry.timer("rag.retrieval.latency").count()).isEqualTo(1);
        assertThat(registry.timer("rag.retrieval.latency").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(120.0);
    }

    @Test
    void toolCallBucketedByStatus() {
        metrics.recordToolCall(RetrievalContext.ToolCall.STATUS_EXECUTED);
        metrics.recordToolCall(RetrievalContext.ToolCall.STATUS_PENDING_APPROVAL);
        metrics.recordToolCall(RetrievalContext.ToolCall.STATUS_REJECTED);

        assertThat(registry.counter("rag.tool.call.total").count()).isEqualTo(3.0);
        assertThat(registry.counter("rag.tool.call.success").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.tool.call.pending").count()).isEqualTo(1.0);
    }

    @Test
    void tokenCountersUnified() {
        metrics.addTokens(150);
        metrics.addTokens(50);
        metrics.recordTokenBudgetRejected();

        assertThat(registry.counter("rag.token.total").count()).isEqualTo(200.0);
        assertThat(registry.counter("rag.token.budget.rejected").count()).isEqualTo(1.0);
    }

    @Test
    void routingCountersSplitByIntent() {
        metrics.recordRoutingChitchat();
        metrics.recordRoutingKnowledge();
        metrics.recordRoutingKnowledge();

        assertThat(registry.counter("rag.routing.chitchat").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.routing.knowledge").count()).isEqualTo(2.0);
    }
}
