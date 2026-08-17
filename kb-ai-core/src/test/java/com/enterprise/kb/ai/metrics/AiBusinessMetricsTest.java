package com.enterprise.kb.ai.metrics;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.security.pii.PiiType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

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

    @Test
    void guardrailCountersSplitByEventType() {
        metrics.recordInjectionBlocked();
        metrics.recordInjectionBlocked();
        metrics.recordPiiMasked(List.of(PiiType.PHONE));
        metrics.recordOutputReplaced("COMPLIANCE_SENSITIVE");
        metrics.recordRateLimited();

        assertThat(registry.counter("rag.guardrail.injection.blocked").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.guardrail.pii.masked").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.output.replaced.compliance_sensitive").count())
            .isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.rate.limited").count()).isEqualTo(1.0);
    }

    @Test
    void outputReplacedFamilySubCountersSplitByClassification() {
        // 安全簇① T5：总项恒计 + 三分类子项 switch 收口；未知族系只计总项
        metrics.recordOutputReplaced("BUSINESS_CONFIDENTIAL");
        metrics.recordOutputReplaced("COMPETITOR_COMPARISON");
        metrics.recordOutputReplaced("COMPETITOR_COMPARISON");
        metrics.recordOutputReplaced("UNCLASSIFIED");
        metrics.recordOutputReplaced(null);

        assertThat(registry.counter("rag.guardrail.output.replaced").count()).isEqualTo(5.0);
        assertThat(registry.counter("rag.guardrail.output.replaced.business_confidential").count())
            .isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.output.replaced.compliance_sensitive").count())
            .isZero();
        assertThat(registry.counter("rag.guardrail.output.replaced.competitor_comparison").count())
            .isEqualTo(2.0);
    }

    @Test
    void outputCanaryCounterRecordsPromptLeakEvents() {
        metrics.recordOutputCanary();

        assertThat(registry.counter("rag.guardrail.output.canary").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.output.replaced").count()).isZero();
    }

    @Test
    void piiMaskedTypeSubCountersSplitByRecognizerType() {
        // 安全簇③ C1/C2：总项恒计一次 + 命中类型子项分列（零标签纪律）
        metrics.recordPiiMasked(List.of(PiiType.PHONE, PiiType.BANK_CARD, PiiType.IPV4));
        metrics.recordPiiMasked(List.of(PiiType.PHONE));

        assertThat(registry.counter("rag.guardrail.pii.masked").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.guardrail.pii.masked.phone").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.guardrail.pii.masked.bank_card").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.pii.masked.ipv4").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.pii.masked.email").count()).isZero();
        assertThat(registry.counter("rag.guardrail.pii.masked.landline").count()).isZero();
    }

    @Test
    void outputPiiEchoCounterRecordsObservationEvents() {
        metrics.recordOutputPiiEcho();

        assertThat(registry.counter("rag.guardrail.output.pii.echo").count()).isEqualTo(1.0);
    }

    @Test
    void indirectInjectionScanCountersRecordHitsAndExclusions() {
        // 安全簇④ D1：命中按条计（warn/exclude 共用 flagged）；exclude 档另计剔除
        metrics.recordIndirectFlagged(2);
        metrics.recordIndirectFlagged(1);
        metrics.recordIndirectExcluded(1);

        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isEqualTo(3.0);
        assertThat(registry.counter("rag.guardrail.indirect.excluded").count()).isEqualTo(1.0);
    }

    // ── FLAG 观察档计数（安全簇① T7：低基数标签 side/family）──

    @Test
    void flaggedAllSideFamilyCombosPreRegistered() {
        // 形态钉死：input 侧注入七分法（含 UNCLASSIFIED）8 条 + output 侧三分类 + UNCLASSIFIED 4 条
        assertThat(registry.find("rag.guardrail.flagged").counters()).hasSize(12);
    }

    @Test
    void flaggedCounterSplitBySideAndFamilyTags() {
        metrics.recordFlagged(AiBusinessMetrics.SIDE_INPUT, "JAILBREAK");
        metrics.recordFlagged(AiBusinessMetrics.SIDE_INPUT, "JAILBREAK");
        metrics.recordFlagged(AiBusinessMetrics.SIDE_OUTPUT, "COMPLIANCE_SENSITIVE");

        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "input", "family", "JAILBREAK").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "output", "family", "COMPLIANCE_SENSITIVE").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "input", "family", "ROLE_HIJACK").count()).isZero();
    }

    @Test
    void flaggedUnknownFamilyFallsBackToUnclassifiedBucket() {
        metrics.recordFlagged(AiBusinessMetrics.SIDE_INPUT, "NOT_A_FAMILY");
        metrics.recordFlagged(AiBusinessMetrics.SIDE_OUTPUT, null);
        metrics.recordFlagged(AiBusinessMetrics.SIDE_INPUT, "  ");

        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "input", "family", "UNCLASSIFIED").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "output", "family", "UNCLASSIFIED").count()).isEqualTo(1.0);
    }

    @Test
    void flaggedUnknownSideNotCountedAnywhere() {
        metrics.recordFlagged("sideways", "JAILBREAK");

        double total = registry.find("rag.guardrail.flagged").counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        assertThat(total).isZero();
    }

    @Test
    void tokenBudgetRejectedCountsInBothDomains() {
        // 同一事件双计数：成本域 rag.token.budget.rejected + 安全域 rag.guardrail.token.budget
        metrics.recordTokenBudgetRejected();

        assertThat(registry.counter("rag.token.budget.rejected").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.token.budget").count()).isEqualTo(1.0);
    }

    @Test
    void requestOutcomeCountersSplitByAuditSemantics() {
        // 与审计三态同语义：SUCCESS 计 total；BusinessException 计 rejected；其他异常计 error
        metrics.recordRequestOutcome(null);
        metrics.recordRequestOutcome(new BusinessException("RATE_LIMITED", "限流"));
        metrics.recordRequestOutcome(new BusinessException("PROMPT_INJECTION", "注入拦截"));
        metrics.recordRequestOutcome(new IllegalStateException("供应商 5xx"));

        assertThat(registry.counter("rag.request.total").count()).isEqualTo(4.0);
        assertThat(registry.counter("rag.request.rejected").count()).isEqualTo(2.0);
        assertThat(registry.counter("rag.request.error").count()).isEqualTo(1.0);
    }

    @Test
    void rerankExecutionAndFallbackCounters() {
        metrics.recordRerank(false);
        metrics.recordRerank(false);
        metrics.recordRerank(true);

        assertThat(registry.counter("rag.rerank.total").count()).isEqualTo(3.0);
        assertThat(registry.counter("rag.rerank.fallback").count()).isEqualTo(1.0);
    }
}
