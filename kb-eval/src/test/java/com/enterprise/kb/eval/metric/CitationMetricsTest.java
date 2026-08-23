package com.enterprise.kb.eval.metric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Citation Attribution 确定性阶段单测（簇② 5.8）——[ref-N] 提取与可解析率
 */
class CitationMetricsTest {

    @Test
    void extractsRefsInOrderWithDuplicates() {
        List<Integer> refs = CitationMetrics.extractRefs(
            "根据资料 [ref-1] 与 [ref-3]，结论如下 [ref-1]。");
        assertThat(refs).containsExactly(1, 3, 1);
    }

    @Test
    void noRefsReturnsEmpty() {
        assertThat(CitationMetrics.extractRefs("无任何引用的回答")).isEmpty();
        assertThat(CitationMetrics.extractRefs(null)).isEmpty();
        assertThat(CitationMetrics.extractRefs("")).isEmpty();
    }

    /** 圈号 ①②③ 与 [ref-⑤] 形态非合法锚点（被测链路引用契约：仅阿拉伯数字） */
    @Test
    void circledDigitsAndMalformedDoNotMatch() {
        assertThat(CitationMetrics.extractRefs("资料①说明 [ref-⑤] 且 [ref-] 与 [refX-2]")).isEmpty();
    }

    @Test
    void resolvableRateAllInRange() {
        assertThat(CitationMetrics.resolvableRate(List.of(1, 2, 3), 5)).isEqualTo(1.0);
    }

    @Test
    void resolvableRatePartialOutOfRange() {
        // [ref-6] 越界（上下文仅 5 条），[ref-0] 越下界 → 1/3 可解析
        assertThat(CitationMetrics.resolvableRate(List.of(1, 6, 0), 5))
            .isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void resolvableRateEmptyRefsIsNaN() {
        assertThat(Double.isNaN(CitationMetrics.resolvableRate(List.of(), 5))).isTrue();
    }
}
