package com.enterprise.kb.eval.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校准打分表解析与 κ 报告单测（簇② 批2）——不启动 Spring 上下文，
 * 直接验证静态解析/统计层（回读器本体仅文件 IO 与落盘）
 */
class CalibrationReadbackRunnerTest {

    private static final String HEADER = "case_id,category,dimension,judge_value,human_a,human_b\n";

    // ── 解析 ──

    @Test
    void parseCsvReadsRowsAndSkipsBlankLines() {
        List<CalibrationReadbackRunner.Row> rows = CalibrationReadbackRunner.parseCsv(HEADER
            + "f-01,FACTOID,faithfulness,4,4,5\n"
            + "\n"
            + "f-02,FACTOID,citation_attribution,SUPPORTED,,\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).caseId()).isEqualTo("f-01");
        assertThat(rows.get(0).dimension()).isEqualTo("faithfulness");
        assertThat(rows.get(0).humanA()).isEqualTo("4");
        assertThat(rows.get(0).humanB()).isEqualTo("5");
        assertThat(rows.get(1).humanA()).isEmpty();
    }

    @Test
    void parseCsvRejectsBrokenHeaderContract() {
        assertThatThrownBy(() -> CalibrationReadbackRunner.parseCsv("a,b,c,d,e,f\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("表头契约");
    }

    @Test
    void parseCsvRejectsBadColumnCountWithLineNumber() {
        assertThatThrownBy(() -> CalibrationReadbackRunner.parseCsv(HEADER
                + "f-01,FACTOID,faithfulness,4,4\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("第 2 行");
    }

    @Test
    void buildReportRejectsUnknownDimension() {
        assertThatThrownBy(() -> CalibrationReadbackRunner.buildReport(
            List.of(new CalibrationReadbackRunner.Row(
                "f-01", "FACTOID", "unknown_dim", "4", "4", "4")), 0.8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown_dim");
    }

    // ── κ 报告：全一致 → 逐维 PASS ──

    @Test
    void allAgreeYieldsPerDimensionPass() {
        String report = CalibrationReadbackRunner.buildReport(
            CalibrationReadbackRunner.parseCsv(HEADER
                // F：1-5 序数双类别，三方全一致（κ=1）
                + "f-01,FACTOID,faithfulness,4,4,4\n"
                + "f-02,FACTOID,faithfulness,5,5,5\n"
                // AC：Judge 有读数但人工未标注 → 待样本（标注批前置的实证形态）
                + "f-01,FACTOID,answer_correctness,4,,\n"
                // CA：NO_CITATION 归并 NOT_SUPPORTED 后全一致（κ=1）
                + "f-01,FACTOID,citation_attribution,SUPPORTED,SUPPORTED,SUPPORTED\n"
                + "f-02,FACTOID,citation_attribution,NO_CITATION,NOT_SUPPORTED,NOT_SUPPORTED\n"
                // HR：Judge 比率二值化（0.0→NONE / 0.25→HAS）与人工 YES/NO 全一致
                + "f-01,FACTOID,hallucination,0.0,NO,NO\n"
                + "f-02,FACTOID,hallucination,0.25,YES,YES\n"
                // NRob：单类别全一致（边际退化 → 约定 κ=1，不得误判未定）
                + "f-01,FACTOID,noise_robustness,CONSISTENT,CONSISTENT,CONSISTENT\n"
                + "f-02,FACTOID,noise_robustness,CONSISTENT,CONSISTENT,CONSISTENT\n"),
            0.8);

        assertThat(report)
            .contains("faithfulness")
            .contains("待样本")
            .doesNotContain("FAIL");
        assertThat(report).contains("总体判定：PASS");
        // E1 口径延续：评分类附 |diff|≤1 一致率
        assertThat(report).contains("100%/100%");
    }

    @Test
    void systematicDisagreementFailsDimension() {
        String report = CalibrationReadbackRunner.buildReport(
            CalibrationReadbackRunner.parseCsv(HEADER
                + "f-01,FACTOID,citation_attribution,SUPPORTED,NOT_SUPPORTED,NOT_SUPPORTED\n"
                + "f-02,FACTOID,citation_attribution,NOT_SUPPORTED,SUPPORTED,SUPPORTED\n"),
            0.8);

        assertThat(report).contains("FAIL");
        assertThat(report).contains("总体判定：FAIL");
    }

    @Test
    void singleAnnotatorFilledFailsJudgeVersusMissingSide() {
        // 仅 A 标注：κ(Judge,B) 未定 → 该维不得 PASS（双标注是交付契约）
        String report = CalibrationReadbackRunner.buildReport(
            CalibrationReadbackRunner.parseCsv(HEADER
                + "f-01,FACTOID,faithfulness,4,4,\n"
                + "f-02,FACTOID,faithfulness,5,5,\n"),
            0.8);

        assertThat(report).contains("FAIL");
        assertThat(report).contains("2/0");
    }

    // ── 归一化语义 ──

    @Test
    void nominalNormalizationSemantics() {
        // CA：NO_CITATION 归并 NOT_SUPPORTED；大小写不敏感；无法识别 → 未标注
        assertThat(CalibrationReadbackRunner.normalizeNominal("citation_attribution", "no_citation"))
            .isEqualTo("NOT_SUPPORTED");
        assertThat(CalibrationReadbackRunner.normalizeNominal("citation_attribution", "Supported"))
            .isEqualTo("SUPPORTED");
        assertThat(CalibrationReadbackRunner.normalizeNominal("citation_attribution", "随便写"))
            .isNull();
        // HR：YES/NO 别名容忍
        assertThat(CalibrationReadbackRunner.normalizeNominal("hallucination", " yes ")).isEqualTo("HAS");
        assertThat(CalibrationReadbackRunner.normalizeNominal("hallucination", "NONE")).isEqualTo("NONE");
        // NRob：仅两判定值合法
        assertThat(CalibrationReadbackRunner.normalizeNominal("noise_robustness", "drifted"))
            .isEqualTo("DRIFTED");
        assertThat(CalibrationReadbackRunner.normalizeNominal("noise_robustness", "MAYBE")).isNull();
        assertThat(CalibrationReadbackRunner.normalizeNominal("faithfulness", null)).isNull();
    }

    @Test
    void hallucinationJudgeRateBinarization() {
        assertThat(CalibrationReadbackRunner.normalizeNominalJudge("hallucination", "0.0")).isEqualTo("NONE");
        assertThat(CalibrationReadbackRunner.normalizeNominalJudge("hallucination", "0.05")).isEqualTo("HAS");
        assertThat(CalibrationReadbackRunner.normalizeNominalJudge("hallucination", "1.0")).isEqualTo("HAS");
        assertThat(CalibrationReadbackRunner.normalizeNominalJudge("hallucination", "不是数字")).isNull();
        // 非 HR 维度走通用归一
        assertThat(CalibrationReadbackRunner.normalizeNominalJudge("citation_attribution", "supported"))
            .isEqualTo("SUPPORTED");
    }
}
