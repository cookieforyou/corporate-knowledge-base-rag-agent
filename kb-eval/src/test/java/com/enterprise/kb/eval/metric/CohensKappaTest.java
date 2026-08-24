package com.enterprise.kb.eval.metric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Cohen's κ 单测（簇② 批2 人类校准通道）
 */
class CohensKappaTest {

    // ── 名义 κ ──

    @Test
    void nominalPerfectAgreementIsOne() {
        assertThat(CohensKappa.nominal(
            List.of("SUPPORTED", "NOT_SUPPORTED", "SUPPORTED"),
            List.of("SUPPORTED", "NOT_SUPPORTED", "SUPPORTED"))).isEqualTo(1.0);
    }

    @Test
    void nominalMatchesHandComputedMatrix() {
        // 经典 2×2：一致 35/50（po=0.70），边际期望一致 0.50 → κ=0.40
        List<String> a = new java.util.ArrayList<>();
        List<String> b = new java.util.ArrayList<>();
        repeat(a, b, 20, "YES", "YES");
        repeat(a, b, 5, "YES", "NO");
        repeat(a, b, 10, "NO", "YES");
        repeat(a, b, 15, "NO", "NO");

        assertThat(CohensKappa.nominal(a, b)).isCloseTo(0.4, within(1e-12));
    }

    private static void repeat(List<String> a, List<String> b, int n, String va, String vb) {
        for (int i = 0; i < n; i++) {
            a.add(va);
            b.add(vb);
        }
    }

    @Test
    void nominalDegenerateSingleCategoryAgreementIsOne() {
        // 全 CONSISTENT：pe=1 但全一致 → 约定 1.0（不得误判未定拖垮校准）
        assertThat(CohensKappa.nominal(
            List.of("CONSISTENT", "CONSISTENT"), List.of("CONSISTENT", "CONSISTENT")))
            .isEqualTo(1.0);
    }

    @Test
    void nominalDisjointLabelSetsIsZero() {
        // A 恒 X / B 恒 Y：边际无共同类别 → pe=0、po=0 → κ=0（等同随机水平）
        assertThat(CohensKappa.nominal(List.of("X", "X"), List.of("Y", "Y"))).isEqualTo(0.0);
    }

    @Test
    void nominalEmptyIsNaN() {
        assertThat(CohensKappa.nominal(List.of(), List.of())).isNaN();
    }

    // ── 二次加权 κ ──

    @Test
    void weightedPerfectAgreementIsOne() {
        assertThat(CohensKappa.weightedQuadratic(List.of(1, 3, 5), List.of(1, 3, 5), 5))
            .isEqualTo(1.0);
    }

    @Test
    void weightedTwoCategoriesEqualsNominal() {
        // k=2 时二次权重退化为名义 κ：po=0.75，pe=0.5 → κ=0.5
        assertThat(CohensKappa.weightedQuadratic(
            List.of(1, 1, 2, 2), List.of(1, 2, 2, 2), 2)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void weightedAdjacentDisagreementMilderThanNominal() {
        // 相邻档分歧（4 vs 5）：加权 κ 应显著高于名义 κ（序数近邻惩罚更轻）
        List<Integer> a = List.of(4, 4, 5, 5, 4, 5);
        List<Integer> b = List.of(5, 4, 5, 4, 4, 5);
        double nominal = CohensKappa.nominal(
            a.stream().map(String::valueOf).toList(),
            b.stream().map(String::valueOf).toList());
        double weighted = CohensKappa.weightedQuadratic(a, b, 5);
        assertThat(weighted).isGreaterThan(nominal);
    }

    @Test
    void weightedDegenerateSingleCategoryIsOne() {
        assertThat(CohensKappa.weightedQuadratic(List.of(3, 3), List.of(3, 3), 5)).isEqualTo(1.0);
    }

    @Test
    void weightedOutOfRangeThrows() {
        assertThatThrownBy(() -> CohensKappa.weightedQuadratic(List.of(0), List.of(3), 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CohensKappa.weightedQuadratic(List.of(6), List.of(3), 5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 通用契约 ──

    @Test
    void sizeMismatchThrows() {
        assertThatThrownBy(() -> CohensKappa.nominal(List.of("A"), List.of("A", "B")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CohensKappa.weightedQuadratic(List.of(1), List.of(), 5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withinOneAgreementSemantics() {
        // |4-5|≤1 一致；|1-5|>1 不一致 → 1/2
        assertThat(CohensKappa.withinOneAgreement(List.of(4, 1), List.of(5, 5))).isEqualTo(0.5);
        assertThat(CohensKappa.withinOneAgreement(List.of(), List.of())).isNaN();
    }
}
