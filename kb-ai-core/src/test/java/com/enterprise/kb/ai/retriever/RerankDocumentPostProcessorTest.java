package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重排序降级路径单测（endpoint 未配置 → fusion_score 截断；API 路径随簇 B 端到端验证）
 */
class RerankDocumentPostProcessorTest {

    private final RetrievalProperties properties = new RetrievalProperties();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AiBusinessMetrics metrics = new AiBusinessMetrics(meterRegistry);

    /** endpoint 为空 → 禁用态，走降级截断（超时参数簇③ D2 引入，禁用态不触达） */
    private final RerankDocumentPostProcessor disabled =
        new RerankDocumentPostProcessor(JsonMapper.builder().build(), properties, metrics,
            "", "qwen3-rerank", "", 5);

    private Document doc(String id, double fusionScore) {
        return Document.builder().id(id).text("t-" + id)
            .metadata(Map.of("fusion_score", fusionScore)).build();
    }

    @Test
    void disabled_truncatesToTopKByFusionScore() {
        // 7 条候选（recallSize 形态），fusion_score 乱序 → 截断至 topK 且按分数降序
        List<Document> candidates = List.of(
            doc("a", 0.010), doc("b", 0.030), doc("c", 0.005), doc("d", 0.025),
            doc("e", 0.020), doc("f", 0.015), doc("g", 0.008));

        List<Document> result = disabled.apply(new Query("q"), candidates);

        assertEquals(properties.getTopK(), result.size());
        // 降序 top5：b(0.030) > d(0.025) > e(0.020) > f(0.015) > a(0.010)；c(0.005)/g(0.008) 落选
        assertEquals(List.of("b", "d", "e", "f", "a"),
            result.stream().map(Document::getId).toList());
    }

    @Test
    void disabled_emptyInput_returnsEmpty() {
        assertTrue(disabled.apply(new Query("q"), List.of()).isEmpty());
    }

    @Test
    void disabled_fewerThanTopK_returnsAllSorted() {
        List<Document> candidates = List.of(doc("x", 0.1), doc("y", 0.5));

        List<Document> result = disabled.apply(new Query("q"), candidates);

        assertEquals(List.of("y", "x"), result.stream().map(Document::getId).toList());
    }

    @Test
    void disabled_missingFusionScore_fallsBackToDocumentScore() {
        Document noMeta = Document.builder().id("z").text("t").score(0.9).build();
        List<Document> candidates = new ArrayList<>(List.of(doc("a", 0.5), noMeta));

        List<Document> result = disabled.apply(new Query("q"), candidates);

        // Document.score=0.9 > fusion_score=0.5 → z 排前
        assertEquals("z", result.get(0).getId());
    }

    /** 检索上下文经 Query.context 参数化传入：最终注入序列记为 source=final（[ref-N] 锚点，11.1.2） */
    @Test
    void recordsFinalTraceEntry_whenContextPresentInQuery() {
        RetrievalContext ctx = new RetrievalContext();
        Query query = Query.builder().text("q")
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx)).build();

        disabled.apply(query, List.of(doc("a", 0.9), doc("b", 0.1)));

        assertEquals(1, ctx.getTraceSummary().size());
        RetrievalContext.TraceEntry entry = ctx.getTraceSummary().get(0);
        assertEquals("final", entry.source());
        assertEquals(List.of("a", "b"), entry.documents().stream().map(Document::getId).toList());
    }

    /** 无检索上下文（kb-eval 形态）：不记录 trace，截断逻辑不受影响 */
    @Test
    void noContext_noTraceRecorded_truncationUnaffected() {
        List<Document> result = disabled.apply(new Query("q"), List.of(doc("a", 0.9)));
        assertEquals(List.of("a"), result.stream().map(Document::getId).toList());
    }

    /** 簇① 指标语义：endpoint 未配置的静态降级不计运行时计数（配置态非运行态，降级率分母不污染） */
    @Test
    void disabled_staticFallback_notCountedInRuntimeMetrics() {
        disabled.apply(new Query("q"), List.of(doc("a", 0.9)));

        assertEquals(0.0, meterRegistry.counter("rag.rerank.total").count());
        assertEquals(0.0, meterRegistry.counter("rag.rerank.fallback").count());
    }

    /** 簇① 指标语义：运行时调用失败降级 → total 与 fallback 各计一次（降级率分子分母齐备） */
    @Test
    void enabledUnreachable_callFails_countsFallbackOnce() {
        RerankDocumentPostProcessor unreachable =
            new RerankDocumentPostProcessor(JsonMapper.builder().build(), properties, metrics,
                "http://127.0.0.1:1", "qwen3-rerank", "sk-test", 1);

        List<Document> result = unreachable.apply(new Query("q"), List.of(doc("a", 0.3), doc("b", 0.7)));

        // 降级路径仍可服务：fusion_score 截断兜底
        assertEquals(List.of("b", "a"), result.stream().map(Document::getId).toList());
        assertEquals(1.0, meterRegistry.counter("rag.rerank.total").count());
        assertEquals(1.0, meterRegistry.counter("rag.rerank.fallback").count());
    }
}
