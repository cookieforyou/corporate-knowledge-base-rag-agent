package com.enterprise.kb.eval.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Judge Prompt 契约单测
 *
 * <p>κ 调优轮（2026-08-28，簇② 批5 κ 定档 FAIL 后）：低 κ 四维判据校准条款
 * 在场性 + 占位符数量钉死（防后续改写丢失校准语义或破坏 String.format 接线）。
 * 判据全文见 16 章 §16.2 κ 调优轮修订注记。
 */
class JudgePromptsTest {

    private static int placeholders(String prompt) {
        int count = 0;
        int idx = 0;
        while ((idx = prompt.indexOf("%s", idx)) >= 0) {
            count++;
            idx += 2;
        }
        return count;
    }

    // ── 占位符接线（EvalRunner String.format 契约）──

    @Test
    void tunedPromptsCarryExactlyThreePlaceholders() {
        assertThat(placeholders(JudgePrompts.FAITHFULNESS)).isEqualTo(3);
        assertThat(placeholders(JudgePrompts.HALLUCINATION_RATE)).isEqualTo(3);
        assertThat(placeholders(JudgePrompts.CITATION_ATTRIBUTION)).isEqualTo(3);
        assertThat(placeholders(JudgePrompts.NOISE_ROBUSTNESS)).isEqualTo(3);

        // format 接线实证：三参无异常且材料落位
        assertThat(String.format(JudgePrompts.FAITHFULNESS, "问", "料", "答")).contains("【回答】");
        assertThat(String.format(JudgePrompts.NOISE_ROBUSTNESS, "问", "A", "B")).contains("【回答 B】");
    }

    // ── F/HR：证据形态识别 + 穷尽核查（治长表格/HTML 素材系统性偏严）──

    @Test
    void faithfulnessCarriesEvidenceRecognitionAndExhaustiveCheck() {
        assertThat(JudgePrompts.FAITHFULNESS)
            .contains("表格")                                  // 证据形态含表格（含 HTML 表格）
            .contains("语义一致即判有依据")                     // 转述/归纳不算无依据
            .contains("穷尽查找")                               // 未穷尽核查不得判低分
            .contains("1-2 分仅适用于");                        // 低分锚点：编造/矛盾/大量无依据
    }

    @Test
    void hallucinationCarriesExhaustiveCheckAndMergeGuard() {
        assertThat(JudgePrompts.HALLUCINATION_RATE)
            .contains("穷尽查找")                               // 同 F：未穷尽不得判无依据
            .contains("合并为一条");                             // 重复表述不重复计入分母
    }

    // ── CA：主判 = 内容支撑（编号偏差不单独判负）──

    @Test
    void citationAttributionCarriesContentSupportPrimacy() {
        assertThat(JudgePrompts.CITATION_ATTRIBUTION)
            .contains("主判 = 陈述内容与参考资料的支撑关系")
            .contains("编号标注偏差")
            .contains("不单独构成 NOT_SUPPORTED")
            .contains("归属造成误导");                           // 例外面仍保留
    }

    // ── NRob：漂移限定证据基结论（单侧编造非噪声来源不计漂移）──

    @Test
    void noiseRobustnessCarriesEvidenceBaseBoundary() {
        assertThat(JudgePrompts.NOISE_ROBUSTNESS)
            .contains("证据基结论")
            .contains("不源自噪声证据")
            .contains("仍判 CONSISTENT")
            .contains("采纳噪声证据内容属漂移");
    }
}
