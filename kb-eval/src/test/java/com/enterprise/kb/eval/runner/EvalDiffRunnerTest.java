package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A/B 差异报表生成器单测（簇② 5.9 批3）——输入解析 / 锚点与配置核验 /
 * 聚合判定 / 逐例翻转与异动 / 用例集差异 / JSON 端到端
 */
class EvalDiffRunnerTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // ── 输入解析 ──

    @Test
    void resolveSourcesByLabelsPointsToSnapshotConvention() {
        List<EvalDiffRunner.DiffSource> sources = EvalDiffRunner.resolveSources("baseline,candidate");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).path()).isEqualTo(Path.of("target", "eval-results-baseline.json"));
        assertThat(sources.get(0).label()).isEqualTo("baseline");
        assertThat(sources.get(1).path()).isEqualTo(Path.of("target", "eval-results-candidate.json"));
        assertThat(sources.get(1).label()).isEqualTo("candidate");
    }

    @Test
    void resolveSourcesByDirectPathsDerivesLabels() {
        List<EvalDiffRunner.DiffSource> sources = EvalDiffRunner.resolveSources(
            "dir/eval-results-a.json,dir/custom-b.json");

        assertThat(sources.get(0).label()).isEqualTo("a");   // 约定前缀剥离
        assertThat(sources.get(1).label()).isEqualTo("custom-b");
    }

    @Test
    void resolveSourcesRejectsBadSpec() {
        assertThatThrownBy(() -> EvalDiffRunner.resolveSources("only-one"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("两个输入");
        assertThatThrownBy(() -> EvalDiffRunner.resolveSources("a,,b"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 报表构建 ──

    private static EvalSnapshot snapshot(String commit, String probe,
                                         EvalSnapshot.Aggregates aggregates,
                                         List<EvalSnapshot.CaseScores> cases) {
        return new EvalSnapshot(
            new GitAnchor(commit.repeat(4), commit.repeat(1) + "abcdef123", "2026-08-24T00:00:00+08:00",
                false, "2026-08-24T01:00:00Z"),
            new EvalSnapshot.RunConfig(probe, 0, 5, false, "qwen3.7-plus", 0.0, false, true, 0, false),
            aggregates,
            cases);
    }

    private static EvalSnapshot.Aggregates aggregates(double recall, double mrr, double faithfulness,
                                                      double hallucinationRate, double negativeRejection) {
        return new EvalSnapshot.Aggregates("hybrid", 10, 8, 8, 2,
            recall, mrr, 0.7, 0, Double.NaN, Double.NaN, Double.NaN,
            faithfulness, 3.9, negativeRejection, 0, Double.NaN, 0, Double.NaN,
            Map.of(),
            new EvalReport.Phase5Metrics(0, Double.NaN, 8, 0.9, 8, hallucinationRate, 0, Double.NaN));
    }

    private static EvalSnapshot.CaseScores caseScores(String id, Double faithfulness, String rejectionVerdict) {
        return new EvalSnapshot.CaseScores(id, "FACTOID", null,
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            faithfulness, 4.0, rejectionVerdict, null, null,
            null, null, null, null, null, null);
    }

    @Test
    void buildReportRendersAnchorsConfigAndAggregateVerdicts() {
        EvalSnapshot a = snapshot("a", "hybrid",
            aggregates(0.90, 0.70, 4.00, 0.06, 0.9),
            List.of(caseScores("f-01", 4.0, "REJECTED")));
        EvalSnapshot b = snapshot("b", "hybrid",
            aggregates(0.85, 0.70, 4.20, 0.02, Double.NaN),
            List.of(caseScores("f-01", 4.0, "REJECTED")));

        String report = EvalDiffRunner.buildReport(a, b, "baseline", "candidate", 0.03);

        // 锚点与配置
        assertThat(report).contains("baseline").contains("candidate")
            .contains("aabcdef123").contains("babcdef123");
        assertThat(report).contains("配置一致——单变量 A/B 假设成立");
        // 聚合判定：方向语义 + 容忍带
        assertThat(report).contains("IMPROVED");   // Faithfulness 4.00→4.20
        assertThat(report).contains("REGRESSED");  // Recall 0.90→0.85
        assertThat(report).contains("STABLE");     // MRR 持平
        assertThat(report).contains("样本缺失");   // Negative Rejection B 侧 NaN
        // Hallucination 越低越好：0.05→0.02 应为 IMPROVED（方向语义核验）
        String hrLine = java.util.Arrays.stream(report.split(System.lineSeparator()))
            .filter(l -> l.startsWith("| Hallucination Rate")).findFirst().orElseThrow();
        assertThat(hrLine).contains("IMPROVED");
    }

    @Test
    void buildReportFlagsConfigMismatch() {
        EvalSnapshot a = snapshot("a", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN), List.of());
        EvalSnapshot b = snapshot("b", "vector-single", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN), List.of());

        String report = EvalDiffRunner.buildReport(a, b, "base", "cand", 0.03);

        assertThat(report).contains("检索探针：A=hybrid / B=vector-single  ⚠ 失配");
        assertThat(report).contains("⚠ 1 项配置失配——存在混杂因素");
    }

    @Test
    void buildReportListsFlipsMovesAnswerChangesAndSetDrift() {
        EvalSnapshot.CaseScores a1 = new EvalSnapshot.CaseScores("f-01", "FACTOID", "hash-old",
            1.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            4.0, 4.0, "REJECTED", null, null, null, null, "SUPPORTED", 1.0, 0.0, null);
        EvalSnapshot.CaseScores b1 = new EvalSnapshot.CaseScores("f-01", "FACTOID", "hash-new",
            1.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            3.5, 4.0, "PARTIAL", null, null, null, null, "SUPPORTED", 1.0, 0.0, null);
        EvalSnapshot.CaseScores aOnly = caseScores("f-02", 4.0, null);
        EvalSnapshot.CaseScores bOnly = caseScores("f-03", 4.0, null);

        EvalSnapshot a = snapshot("a", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN),
            List.of(a1, aOnly));
        EvalSnapshot b = snapshot("b", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN),
            List.of(b1, bOnly));

        String report = EvalDiffRunner.buildReport(a, b, "base", "cand", 0.03);

        assertThat(report).contains("f-01 / 负向拒答：REJECTED → PARTIAL");       // 判定翻转
        assertThat(report).contains("f-01：").contains("Faithfulness 4.000→3.500"); // 数值异动
        assertThat(report).contains("答案变化 1 例：f-01");                       // 指纹变化
        assertThat(report).contains("仅 A 有：f-02").contains("仅 B 有：f-03");   // 集合漂移
    }

    @Test
    void buildReportHandlesIdenticalRunsCleanly() {
        EvalSnapshot a = snapshot("a", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN),
            List.of(caseScores("f-01", 4.0, "REJECTED")));
        EvalSnapshot b = snapshot("b", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN),
            List.of(caseScores("f-01", 4.0, "REJECTED")));

        String report = EvalDiffRunner.buildReport(a, b, "base", "cand", 0.03);

        assertThat(report)
            .contains("用例集一致（共 1 例）")
            .contains("答案指纹全部一致")
            .contains("0 项 IMPROVED / 0 项 REGRESSED");
    }

    // ── 端到端：JSON 快照 → run() → 差异报表落盘 ──

    @Test
    void runConsumesSnapshotFilesAndWritesDiffReport(@TempDir Path tempDir) throws Exception {
        EvalSnapshot a = snapshot("a", "hybrid", aggregates(0.9, 0.7, 4.0, 0.05, Double.NaN),
            List.of(caseScores("f-01", 4.0, "REJECTED")));
        EvalSnapshot b = snapshot("b", "hybrid", aggregates(0.9, 0.7, 4.3, 0.05, Double.NaN),
            List.of(caseScores("f-01", 4.0, "REJECTED")));
        Path pathA = tempDir.resolve("eval-results-baseA.json");
        Path pathB = tempDir.resolve("eval-results-baseB.json");
        Files.writeString(pathA, jsonMapper.writeValueAsString(a));
        Files.writeString(pathB, jsonMapper.writeValueAsString(b));

        EvalDiffRunner runner = new EvalDiffRunner(new EvalProperties(), jsonMapper);
        runner.run(new DefaultApplicationArguments(
            "--eval.diff=" + pathA + "," + pathB));

        Path out = Path.of("target", "eval-diff-baseA-baseB.md");
        assertThat(out).exists();
        String report = Files.readString(out);
        assertThat(report).contains("Eval A/B 差异报表")
            .contains("Faithfulness")   // 聚合对照渲染（4.3 经 JSON 往返后仍可比）
            .contains("IMPROVED");
    }

    @Test
    void runFailsFastWhenSnapshotMissing() {
        EvalDiffRunner runner = new EvalDiffRunner(new EvalProperties(), jsonMapper);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
            "--eval.diff=no-such-label-a,no-such-label-b")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("评估快照不存在");
    }

    @Test
    void runIgnoresAbsentOption() {
        EvalDiffRunner runner = new EvalDiffRunner(new EvalProperties(), jsonMapper);
        runner.run(new DefaultApplicationArguments()); // 无 eval.diff 选项：静默返回
    }
}
