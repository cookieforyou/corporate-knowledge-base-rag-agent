package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 间接注入扫描后处理器测试（安全簇④ D1）——合成词表驱动（敏感词纪律：
 * 测试以占位词项构造，不入真词面；词值均为无语义占位串）。
 */
class IndirectInjectionScanPostProcessorTest {

    /** 占位词干（无语义，仅驱动匹配机器） */
    private static final String PLACEHOLDER_KEYWORD = "zz-synthetic-token";

    private AiBusinessMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AiBusinessMetrics(registry);
    }

    private static GuardrailRule keywordRule(String id, RuleAction action) {
        return new GuardrailRule(id, "UNCLASSIFIED", "", RuleType.KEYWORD,
            PLACEHOLDER_KEYWORD, action, true, null);
    }

    private static Document document(String id, String text) {
        return Document.builder().id(id).text(text)
            .metadata(Map.of("doc_name", "t.txt")).build();
    }

    private static IndirectInjectionScanPostProcessor processor(
            AiBusinessMetrics metrics, boolean enabled, String strategy, RuleAction action) {
        return new IndirectInjectionScanPostProcessor(
            List.of(keywordRule("syn-01", action)), metrics, enabled, strategy);
    }

    @Test
    void cleanDocumentsPassThroughUntouchedWithZeroCounters() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "warn", RuleAction.BLOCK);
        Document clean = document("d1", "正常的业务资料文本");

        List<Document> result = processor.process(new Query("问题"), List.of(clean));

        assertThat(result).containsExactly(clean);   // 同一实例直通（零拷贝零漂移）
        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isZero();
        assertThat(registry.counter("rag.guardrail.indirect.excluded").count()).isZero();
    }

    @Test
    void warnStrategyMarksHitDocumentAndKeepsIt() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "warn", RuleAction.BLOCK);
        Document hit = document("d1", "资料正文包含 " + PLACEHOLDER_KEYWORD + " 片段");
        Document clean = document("d2", "干净资料");

        List<Document> result = processor.process(new Query("问题"), List.of(hit, clean));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getMetadata())
            .containsEntry(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY, Boolean.TRUE)
            .containsEntry("doc_name", "t.txt");      // 既有元数据保留
        assertThat(result.get(0).getText()).isEqualTo(hit.getText());   // 正文零回写（检测视图纪律）
        assertThat(result.get(1).getMetadata())
            .doesNotContainKey(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY);
        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.indirect.excluded").count()).isZero();
    }

    @Test
    void excludeStrategyDropsHitDocumentAndCountsBoth() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "exclude", RuleAction.BLOCK);
        Document hit = document("d1", "包含 " + PLACEHOLDER_KEYWORD);
        Document clean = document("d2", "干净资料");

        List<Document> result = processor.process(new Query("问题"), List.of(hit, clean));

        assertThat(result).containsExactly(clean);
        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.indirect.excluded").count()).isEqualTo(1.0);
    }

    @Test
    void excludeAllYieldsEmptyListForRefusalFallback() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "exclude", RuleAction.BLOCK);
        Document hit1 = document("d1", PLACEHOLDER_KEYWORD + " 一");
        Document hit2 = document("d2", PLACEHOLDER_KEYWORD + " 二");

        List<Document> result = processor.process(new Query("问题"), List.of(hit1, hit2));

        assertThat(result).isEmpty();   // 空证据链路自然回落 EMPTY_CONTEXT_PROMPT 拒答
    }

    @Test
    void disabledProcessorPassesThroughWithoutScan() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, false, "warn", RuleAction.BLOCK);
        Document hit = document("d1", "包含 " + PLACEHOLDER_KEYWORD);

        List<Document> result = processor.process(new Query("问题"), List.of(hit));

        assertThat(result).containsExactly(hit);
        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isZero();
    }

    @Test
    void flagActionAlsoTriggersDetectionView() {
        // 检测视图口径：BLOCK+FLAG 全档命中即判中（间接面不拒绝，只警示/剔除）
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "warn", RuleAction.FLAG);
        Document hit = document("d1", "包含 " + PLACEHOLDER_KEYWORD);

        List<Document> result = processor.process(new Query("问题"), List.of(hit));

        assertThat(result.get(0).getMetadata())
            .containsKey(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY);
        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isEqualTo(1.0);
    }

    @Test
    void normalizedDetectionViewCatchesFullwidthVariant() {
        // S1 归一化检测视图：全角变体经 NFKC 还原后命中（与输入侧同口径）——
        // 占位串的全角形态程序化构造，零真词面
        String fullwidthVariant = PLACEHOLDER_KEYWORD.chars()
            .mapToObj(c -> String.valueOf((char) (c - 'a' + 0xFF41)))
            .reduce(String::concat).orElseThrow();
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "warn", RuleAction.BLOCK);
        Document hit = document("d1", "包含 " + fullwidthVariant);

        List<Document> result = processor.process(new Query("问题"), List.of(hit));

        assertThat(result.get(0).getMetadata())
            .containsKey(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY);
    }

    @Test
    void invalidStrategyFallsBackToWarnSemantics() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "bogus", RuleAction.BLOCK);
        Document hit = document("d1", "包含 " + PLACEHOLDER_KEYWORD);

        List<Document> result = processor.process(new Query("问题"), List.of(hit));

        assertThat(result).hasSize(1);   // warn 语义：打标保留不剔除
        assertThat(result.get(0).getMetadata())
            .containsKey(IndirectInjectionScanPostProcessor.INDIRECT_HIT_KEY);
        assertThat(registry.counter("rag.guardrail.indirect.excluded").count()).isZero();
    }

    @Test
    void multipleHitsCountPerDocument() {
        IndirectInjectionScanPostProcessor processor = processor(metrics, true, "warn", RuleAction.BLOCK);
        List<Document> documents = List.of(
            document("d1", PLACEHOLDER_KEYWORD + " 一"),
            document("d2", PLACEHOLDER_KEYWORD + " 二"),
            document("d3", "干净资料"));

        processor.process(new Query("问题"), documents);

        assertThat(registry.counter("rag.guardrail.indirect.flagged").count()).isEqualTo(2.0);
    }
}
