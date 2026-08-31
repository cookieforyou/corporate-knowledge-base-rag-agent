package com.enterprise.kb.eval.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * judge 畸形输出剥壳容错单测（簇② md1-final 判读落地，16 章 v2.87）。
 * 两种实测畸形形态 + 不静默给分纪律的边界。
 */
class TolerantJudgeScoreConverterTest {

    private final TolerantJudgeScoreConverter converter = new TolerantJudgeScoreConverter();

    @Test
    void legalJsonPassesThrough() {
        JudgePrompts.JudgeScore js = converter.convert(
            "{\"score\": 4, \"reason\": \"逐条核查有依据\", \"verdict\": \"PASS\"}");
        assertThat(js.score()).isEqualTo(4);
        assertThat(js.reason()).contains("核查");
    }

    @Test
    void fencedJsonStillWorks() {
        JudgePrompts.JudgeScore js = converter.convert(
            "```json\n{\"score\": 5, \"reason\": \"完全忠于资料\"}\n```");
        assertThat(js.score()).isEqualTo(5);
    }

    /** 畸形形态②：裸 token 前缀（非 JSON 文本包裹合法对象）→ 剥壳救回 */
    @Test
    void bareTokenPrefixSalvaged() {
        JudgePrompts.JudgeScore js = converter.convert(
            "reason: 核查了全部条目 score: 4 {\"score\": 4, \"reason\": \"有依据\"}");
        assertThat(js.score()).isEqualTo(4);
    }

    /** 首个对象 score 缺位（键值错位残骸）→ 跳过取下一个平衡对象 */
    @Test
    void skipsObjectWithMissingScore() {
        JudgePrompts.JudgeScore js = converter.convert(
            "{\"verdict\": \"PASS\"} 后续 {\"score\": 3, \"reason\": \"少量无依据\"}");
        assertThat(js.score()).isEqualTo(3);
    }

    /** reason 字符串含大括号/转义引号 → 字符串感知配对不误截 */
    @Test
    void bracesInsideStringLiteralDoNotBreakBalancing() {
        JudgePrompts.JudgeScore js = converter.convert(
            "前缀噪声 {\"score\": 2, \"reason\": \"声称引用了 {\\\"ref-3\\\"} 但资料无此条\"}");
        assertThat(js.score()).isEqualTo(2);
        assertThat(js.reason()).contains("ref-3");
    }

    /** 无任何可解析对象 → 上抛（既有「评估失败」剔除路径，不静默给分） */
    @Test
    void unparseableTextThrows() {
        assertThatThrownBy(() -> converter.convert("reason: 纯文本无 JSON"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不可解析");
    }

    /** 键值错位形态①：对象语法合法但 score 位是字符串 → 无法救回即上抛 */
    @Test
    void structurallyWrongScoreFieldThrows() {
        assertThatThrownBy(() -> converter.convert(
            "{\"score\": \"reason\", \"reason\": {\"score\": 4}}"))
            .isInstanceOf(IllegalStateException.class);
    }

    /** 截断输出（不平衡对象）→ 上抛 */
    @Test
    void truncatedObjectThrows() {
        assertThatThrownBy(() -> converter.convert("{\"score\": 4, \"reason\": \"截断"))
            .isInstanceOf(IllegalStateException.class);
    }
}
