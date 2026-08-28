package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 扩展指标单测（簇② 5.8）——噪声抽样映射 / 编号化混排 / 聚合与报告渲染
 */
class EvalRunnerPhase5Test {

    private final EvalProperties props = new EvalProperties();

    private EvalRunner runner() {
        RetrievalProbe stub = new RetrievalProbe() {
            @Override
            public List<ProbeHit> probe(String query, int topK) {
                return List.of();
            }

            @Override
            public String name() {
                return "stub";
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
        return new EvalRunner(null, List.of(stub), null, null, null, null, null, null, props,
            tools.jackson.databind.json.JsonMapper.builder().build(), null);
    }

    private static GoldenQAPair pair(String id, QACategory category, String question) {
        return new GoldenQAPair(id, category, question, null, null, null, null, null, null, null);
    }

    // ── noiseQueryMap：确定性抽样与噪声源映射 ──

    @Test
    void noiseSampleTakesFirstNPositiveCasesWithNextQuestionAsNoise() {
        props.getMetrics().setNoiseSampleSize(2);
        List<GoldenQAPair> dataset = List.of(
            pair("f-01", QACategory.FACTOID, "问题一"),
            pair("neg-01", QACategory.NEGATIVE, "负向问题"),
            pair("f-02", QACategory.FACTOID, "问题二"),
            pair("t-01", QACategory.TABLE, "表格问题"));

        Map<String, String> map = runner().noiseQueryMap(dataset);

        // 前 2 条正向用例 = f-01 / f-02（NEGATIVE 跳过）；噪声源 = 数据集内下一条（循环）
        assertThat(map).hasSize(2)
            .containsEntry("f-01", "负向问题")
            .containsEntry("f-02", "表格问题");
    }

    @Test
    void noiseSourceWrapsAroundAtDatasetEnd() {
        props.getMetrics().setNoiseSampleSize(1);
        List<GoldenQAPair> dataset = List.of(
            pair("neg-01", QACategory.NEGATIVE, "负向问题"),
            pair("f-01", QACategory.FACTOID, "问题一"));

        // f-01 为末条正向用例 → 噪声源循环回数据集首条
        assertThat(runner().noiseQueryMap(dataset)).containsEntry("f-01", "负向问题");
    }

    @Test
    void noiseMapEmptyWhenDisabledOrRetrievalOnly() {
        List<GoldenQAPair> dataset = List.of(
            pair("f-01", QACategory.FACTOID, "问题一"),
            pair("f-02", QACategory.FACTOID, "问题二"));

        // 缺省样本数 0 = 关
        assertThat(runner().noiseQueryMap(dataset)).isEmpty();

        props.getMetrics().setNoiseSampleSize(2);
        props.setRetrievalOnly(true);
        assertThat(runner().noiseQueryMap(dataset)).isEmpty();

        props.setRetrievalOnly(false);
        props.getMetrics().setPhase5Enabled(false);
        assertThat(runner().noiseQueryMap(dataset)).isEmpty();
    }

    // ── numberedContext：编号续接契约（与 RetrievalConfig#formatNumberedContext 同形）──

    @Test
    void numberedContextContinuesNumberingIntoNoise() {
        List<RetrievalProbe.ProbeHit> base = List.of(
            new RetrievalProbe.ProbeHit("c1", "a.md", "原文一", 0.9),
            new RetrievalProbe.ProbeHit("c2", "b.md", "原文二", 0.8));
        List<RetrievalProbe.ProbeHit> noise = List.of(
            new RetrievalProbe.ProbeHit("n1", "c.md", "噪声一", 0.7));

        String ctx = EvalRunner.numberedContext(base, noise);

        assertThat(ctx)
            .contains("[ref-1]\n原文一")
            .contains("[ref-2]\n原文二")
            .contains("[ref-3]\n噪声一");
    }

    // ── aggregatePhase5：聚合语义 ──

    private static EvalResult genResult(String id, Double ac, String caVerdict, Double hr, String noise) {
        GoldenQAPair pair = pair(id, QACategory.FACTOID, "问题-" + id);
        return new EvalResult(pair, List.of(), "回答", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, 4.5, 4.5, null, null, null, null, null,
            null, ac, caVerdict, caVerdict == null ? null : 1.0, hr, noise, null);
    }

    @Test
    void aggregateComputesRatesAndMeans() {
        List<EvalResult> results = new ArrayList<>();
        results.add(genResult("a", 5.0, "SUPPORTED", 0.0, "CONSISTENT"));
        results.add(genResult("b", 3.0, "NO_CITATION", 0.10, "DRIFTED"));
        results.add(genResult("c", null, "NOT_SUPPORTED", 0.05, null));

        EvalReport.Phase5Metrics m = EvalRunner.aggregatePhase5(results);

        assertThat(m.answerCorrectnessEvaluated()).isEqualTo(2);
        assertThat(m.avgAnswerCorrectness()).isEqualTo(4.0);
        // CA 分母 = 3（含 NO_CITATION 判负），通过 1 → 1/3
        assertThat(m.citationEvaluated()).isEqualTo(3);
        assertThat(m.citationPassRate()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.hallucinationEvaluated()).isEqualTo(3);
        assertThat(m.avgHallucinationRate()).isCloseTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.noiseEvaluated()).isEqualTo(2);
        assertThat(m.noiseConsistencyRate()).isEqualTo(0.5);
    }

    @Test
    void aggregateAllNullReturnsEmpty() {
        assertThat(EvalRunner.aggregatePhase5(List.of(genResult("a", null, null, null, null))))
            .isSameAs(EvalReport.Phase5Metrics.EMPTY);
    }

    // ── 报告渲染：观察带小节 ──

    private static EvalReport reportWith(EvalReport.Phase5Metrics phase5) {
        return new EvalReport("chain", 3, 0, 3, 0,
            Double.NaN, Double.NaN, Double.NaN,
            0, Double.NaN, Double.NaN, Double.NaN,
            4.5, 4.5, Double.NaN,
            0, Double.NaN, 0, Double.NaN, Map.of(), List.of(), phase5);
    }

    @Test
    void summaryRendersPhase5SectionWhenDataPresent() {
        EvalReport.Phase5Metrics m = new EvalReport.Phase5Metrics(
            2, 4.0, 3, 1.0 / 3, 3, 0.05, 2, 0.5);
        String summary = reportWith(m).summary();
        assertThat(summary)
            .contains("生成侧扩展（Phase 5 观察带）")
            .contains("Answer Correctness")
            .contains("Citation Support Rate")
            .contains("Hallucination Rate:    5.0%")
            .contains("Noise Consistency");
    }

    @Test
    void summaryOmitsPhase5SectionWhenEmpty() {
        assertThat(reportWith(EvalReport.Phase5Metrics.EMPTY).summary())
            .doesNotContain("生成侧扩展");
    }

    /** 观察带纪律回归钉死：扩展指标低分不触发门禁（校准前只报告） */
    @Test
    void phase5MetricsDoNotGateYet() {
        EvalReport.Phase5Metrics low = new EvalReport.Phase5Metrics(
            2, 1.0, 3, 0.0, 3, 0.9, 2, 0.0);
        org.assertj.core.api.Assertions.assertThatCode(
                () -> reportWith(low).assertThresholds(props))
            .doesNotThrowAnyException();
    }

    // ── 人类校准表（簇② 批2）：CSV 打分表 + MD 材料 ──

    /** 全维度结果：F=4、AC=5、CA=SUPPORTED、HR=0.25、NR=CONSISTENT（带答案 B） */
    private static EvalResult fullResult(String id) {
        GoldenQAPair pair = new GoldenQAPair(id, QACategory.TABLE, "问题-" + id,
            null, "理想回答-" + id, null, null, null, null, null);
        return new EvalResult(pair, List.of(), "回答-" + id, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, 4.0, 4.0, null, null, null, null, null,
            null, 5.0, "SUPPORTED", 1.0, 0.25, "CONSISTENT", "答案B-" + id);
    }

    @Test
    void calibrationCsvCarriesAllPresentDimensions() {
        String csv = EvalRunner.renderCalibrationCsv(List.of(fullResult("t-01")));

        assertThat(csv).startsWith("case_id,category,dimension,judge_value,human_a,human_b\n");
        assertThat(csv)
            .contains("t-01,TABLE,faithfulness,4,,\n")
            .contains("t-01,TABLE,answer_correctness,5,,\n")
            .contains("t-01,TABLE,citation_attribution,SUPPORTED,,\n")
            .contains("t-01,TABLE,hallucination,0.25,,\n")
            .contains("t-01,TABLE,noise_robustness,CONSISTENT,,\n");
    }

    @Test
    void calibrationCsvOmitsAbsentDimensionsAndKeepsNoCitationRaw() {
        // 仅 F 与 CA=NO_CITATION：CSV 原样携带（归并语义在回读层，生成层不丢信息）
        EvalResult minimal = new EvalResult(pair("f-01", QACategory.FACTOID, "问题"),
            List.of(), "回答", Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, Double.NaN, 3.0, 3.0, null, null, null, null, null,
            null, null, "NO_CITATION", null, null, null, null);

        String csv = EvalRunner.renderCalibrationCsv(List.of(minimal));

        assertThat(csv)
            .contains("f-01,FACTOID,faithfulness,3,,\n")
            .contains("f-01,FACTOID,citation_attribution,NO_CITATION,,\n")
            .doesNotContain("answer_correctness")
            .doesNotContain("hallucination")
            .doesNotContain("noise_robustness");
    }

    @Test
    void agreementSheetMaterialsCarryPhase5ReadingsAndNoiseAnswer() {
        String md = runner().renderAgreementSheet(List.of(fullResult("t-01")));

        assertThat(md)
            .contains("人类校准打分材料")
            .contains("faithfulness")
            .contains("answer_correctness")
            .contains("citation_attribution")
            .contains("hallucination")
            .contains("noise_robustness")
            .contains("理想回答-t-01")          // AC 人审对照的理想回答材料
            .contains("答案 B（混噪生成，NRob 对照）")
            .contains("答案B-t-01")              // NRob 人审需见答案 B
            .contains("Hallucination Rate = 25.0%")
            .contains("Citation Attribution = SUPPORTED");
    }

    // ── 运行锚点头（簇② 5.9 批3） ──

    @Test
    void anchorHeaderCarriesGitFormAndDirtyMarker() {
        GitAnchor anchor = new GitAnchor("a".repeat(40), "a".repeat(10),
            "2026-08-20T10:00:00+08:00", true, "2026-08-24T01:00:00Z");

        String header = EvalRunner.renderAnchorHeader(anchor, "baseline");

        assertThat(header)
            .contains("运行标签:  baseline")
            .contains("a".repeat(10))
            .contains("2026-08-20T10:00:00+08:00")
            .contains("脏 ⚠")                    // 工作区脏显式标记，锚点不完全代表代码形态
            .contains("2026-08-24T01:00:00Z");
    }

    @Test
    void anchorHeaderDegradesGracefullyOutsideGit() {
        GitAnchor anchor = new GitAnchor(GitAnchor.UNKNOWN, GitAnchor.UNKNOWN, GitAnchor.UNKNOWN,
            false, "2026-08-24T01:00:00Z");

        String header = EvalRunner.renderAnchorHeader(anchor, null);

        assertThat(header)
            .contains("（无）")                   // 空标签渲染
            .contains(GitAnchor.UNKNOWN)
            .doesNotContain("脏 ⚠");
    }
}
