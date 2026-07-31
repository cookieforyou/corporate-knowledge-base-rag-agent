package com.enterprise.kb.eval.metric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 检索指标纯函数单元测试（无 Spring 上下文，CI 常驻）
 */
class RetrievalMetricsTest {

    @Test
    void recallAtK_allHit() {
        assertEquals(1.0, RetrievalMetrics.recallAtK(
            List.of("a", "b", "c"), List.of("a", "b")));
    }

    @Test
    void recallAtK_partialHit() {
        assertEquals(0.5, RetrievalMetrics.recallAtK(
            List.of("a", "x", "y"), List.of("a", "b")));
    }

    @Test
    void recallAtK_noHit() {
        assertEquals(0.0, RetrievalMetrics.recallAtK(
            List.of("x", "y"), List.of("a")));
    }

    @Test
    void recallAtK_emptyExpected_isNaN() {
        assertTrue(Double.isNaN(RetrievalMetrics.recallAtK(List.of("a"), List.of())));
    }

    @Test
    void reciprocalRank_firstPosition() {
        assertEquals(1.0, RetrievalMetrics.reciprocalRank(
            List.of("a", "b", "c"), List.of("a")));
    }

    @Test
    void reciprocalRank_thirdPosition() {
        assertEquals(1.0 / 3, RetrievalMetrics.reciprocalRank(
            List.of("x", "y", "a"), List.of("a")), 1e-9);
    }

    @Test
    void reciprocalRank_usesEarliestHit() {
        // 多个期望 Chunk 命中时取最早排名
        assertEquals(0.5, RetrievalMetrics.reciprocalRank(
            List.of("x", "b", "a"), List.of("a", "b")), 1e-9);
    }

    @Test
    void reciprocalRank_noHit() {
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(
            List.of("x", "y"), List.of("a")));
    }

    @Test
    void contextPrecision_allRelevantFirst() {
        // 前两个都相关：(1/1 + 2/2) / 2 = 1.0
        assertEquals(1.0, RetrievalMetrics.contextPrecision(
            List.of("a", "b", "x"), List.of("a", "b")), 1e-9);
    }

    @Test
    void contextPrecision_relevantLater_rankPenalized() {
        // 仅第 3 位相关：(1/3) / 1 ≈ 0.333，排名靠后被惩罚
        assertEquals(1.0 / 3, RetrievalMetrics.contextPrecision(
            List.of("x", "y", "a"), List.of("a")), 1e-9);
    }

    @Test
    void contextPrecision_mixed() {
        // 第 1、3 位相关：(1/1 + 2/3) / 2 ≈ 0.833
        assertEquals((1.0 + 2.0 / 3) / 2, RetrievalMetrics.contextPrecision(
            List.of("a", "x", "b"), List.of("a", "b")), 1e-9);
    }

    @Test
    void contextPrecision_emptyExpected_isNaN() {
        assertTrue(Double.isNaN(RetrievalMetrics.contextPrecision(List.of("a"), null)));
    }
}
