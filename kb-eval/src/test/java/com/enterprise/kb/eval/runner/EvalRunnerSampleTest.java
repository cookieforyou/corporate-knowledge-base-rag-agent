package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工-Judge 一致率分层抽样单测（簇④ E1）
 */
class EvalRunnerSampleTest {

    private static EvalResult result(String id, QACategory category) {
        GoldenQAPair pair = new GoldenQAPair(id, category, "问题-" + id, null, null, null, null);
        return new EvalResult(pair, List.of(), "回答", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, 4.0, 4.0, null, null, null);
    }

    /** 镜像真实 Golden 分布：FACTOID 39 / REASONING 17 / TABLE 15 / MULTI_DOC 9 = 80 */
    private static List<EvalResult> realDistribution() {
        List<EvalResult> all = new ArrayList<>();
        for (int i = 0; i < 39; i++) all.add(result("factoid-" + i, QACategory.FACTOID));
        for (int i = 0; i < 17; i++) all.add(result("reasoning-" + i, QACategory.REASONING));
        for (int i = 0; i < 15; i++) all.add(result("table-" + i, QACategory.TABLE));
        for (int i = 0; i < 9; i++) all.add(result("multi-" + i, QACategory.MULTI_DOC));
        return all;
    }

    @Test
    void quotasProportionalToCategorySizes() {
        List<EvalResult> sampled = EvalRunner.stratifiedSample(realDistribution(), 20, 42L);
        assertThat(sampled).hasSize(20);
        Map<QACategory, Long> counts = sampled.stream()
            .collect(Collectors.groupingBy(r -> r.pair().category(), Collectors.counting()));
        // 20 × 占比：FACTOID 9.75 → 10，TABLE 3.75 → 4，REASONING 4.25 → 4，MULTI_DOC 2.25 → 2
        assertThat(counts)
            .containsEntry(QACategory.FACTOID, 10L)
            .containsEntry(QACategory.TABLE, 4L)
            .containsEntry(QACategory.REASONING, 4L)
            .containsEntry(QACategory.MULTI_DOC, 2L);
    }

    @Test
    void sameSeedReproducesSameSample() {
        List<String> first = EvalRunner.stratifiedSample(realDistribution(), 20, 42L).stream()
            .map(r -> r.pair().id()).sorted().toList();
        List<String> second = EvalRunner.stratifiedSample(realDistribution(), 20, 42L).stream()
            .map(r -> r.pair().id()).sorted().toList();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void sampleSizeCappedByPopulation() {
        List<EvalResult> sampled = EvalRunner.stratifiedSample(realDistribution(), 500, 42L);
        assertThat(sampled).hasSize(80);
    }
}
