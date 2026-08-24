package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * A/B 双跑差异报表生成器（簇② 5.9 批3，16 章 §16.5）
 *
 * <p>用法（Prompt Git Ops 4.8 形态——prompt 版本即 git 版本，不新建抽象层）：
 * <ol>
 *   <li>基线版本全量评估：{@code EVAL_RUN_LABEL=baseline mvn spring-boot:run -pl kb-eval}
 *       → 落 {@code target/eval-results-baseline.json}（快照随报告产出）；</li>
 *   <li>切换候选版本（git 切换 / prompt 改动）复跑：
 *       {@code EVAL_RUN_LABEL=candidate mvn spring-boot:run -pl kb-eval}；</li>
 *   <li>差异报表：{@code --eval.diff=baseline,candidate}
 *       → {@code target/eval-diff-baseline-candidate.md}（stdout 同步直出）。</li>
 * </ol>
 *
 * <p>报表面：双跑锚点（git hash + 提交/运行时刻 + 脏标记）+ 配置一致性核验
 * （单变量 A/B 假设，失配项 ⚠）+ 聚合指标 Δ（对照 {@code eval.thresholds.regression}
 * 容忍带判 IMPROVED/REGRESSED/STABLE）+ 分类分解 + 逐例判定翻转/数值异动/
 * 答案指纹变化 + 用例集差异。零 LLM 调用——纯快照消费。
 *
 * <p>内容盲纪律承自 {@link EvalSnapshot}：报表只含用例 ID、数值、verdict 与
 * 计数，不回显问句/回答正文。
 */
@Slf4j
@Component
public class EvalDiffRunner implements ApplicationRunner {

    private final EvalProperties props;
    private final JsonMapper jsonMapper;

    public EvalDiffRunner(EvalProperties props, JsonMapper jsonMapper) {
        this.props = props;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> values = args.getOptionValues("eval.diff");
        String spec = values == null ? null : values.stream().findFirst().orElse(null);
        if (spec == null || spec.isBlank()) {
            return;
        }
        List<DiffSource> sources = resolveSources(spec);
        EvalSnapshot a = load(sources.get(0));
        EvalSnapshot b = load(sources.get(1));
        String report = buildReport(a, b, sources.get(0).label(), sources.get(1).label(),
            props.getThresholds().getRegression());
        System.out.println(report);
        log.info("\n{}", report);
        try {
            Path out = Path.of("target",
                "eval-diff-" + sources.get(0).label() + "-" + sources.get(1).label() + ".md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report + System.lineSeparator());
            log.info("A/B 差异报表已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("A/B 差异报表落盘失败: {}", e.getMessage());
        }
    }

    /** diff 输入源：标签（解析为 target/eval-results-{label}.json）或直连路径 */
    record DiffSource(Path path, String label) {}

    /**
     * 输入解析：{@code labelA,labelB} 两段；每段以 {@code .json} 结尾或含路径分隔符
     * 时按直连路径消费，否则按标签解析为 {@code target/eval-results-<label>.json}。
     */
    static List<DiffSource> resolveSources(String spec) {
        String[] parts = spec.split(",");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException(
                "--eval.diff 需两个输入：<labelA>,<labelB>（解析为 target/eval-results-{label}.json）"
                    + "或 <pathA.json>,<pathB.json>（直连路径）——实际：" + spec);
        }
        return Arrays.stream(parts).map(String::strip).map(part -> {
            if (part.endsWith(".json") || part.contains("/") || part.contains("\\")) {
                Path path = Path.of(part);
                String name = path.getFileName().toString().replaceAll("\\.json$", "");
                String label = name.startsWith("eval-results-")
                    ? name.substring("eval-results-".length()) : name;
                return new DiffSource(path, label);
            }
            return new DiffSource(Path.of("target", "eval-results-" + part + ".json"), part);
        }).toList();
    }

    private EvalSnapshot load(DiffSource source) {
        if (!Files.exists(source.path())) {
            throw new IllegalStateException("评估快照不存在：" + source.path()
                + "——请先以 EVAL_RUN_LABEL=" + source.label() + " 跑全量评估（快照随报告落盘）");
        }
        try {
            return jsonMapper.readValue(Files.readString(source.path()), EvalSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("评估快照解析失败：" + source.path(), e);
        }
    }

    // ── 报表构建（纯函数，单测面） ──

    /** 聚合指标定义：方向语义（hallucinationRate 越低越好，其余越高越好） */
    record MetricDef(String label, boolean higherBetter, ToDoubleFunction<EvalSnapshot.Aggregates> value) {}

    static final List<MetricDef> AGGREGATE_METRICS = List.of(
        new MetricDef("Top-K Recall", true, m -> m.avgRecall()),
        new MetricDef("MRR", true, m -> m.avgMrr()),
        new MetricDef("Context Precision", true, m -> m.avgContextPrecision()),
        new MetricDef("Doc Recall", true, m -> m.avgDocRecall()),
        new MetricDef("Doc MRR", true, m -> m.avgDocMrr()),
        new MetricDef("Doc Context Precision", true, m -> m.avgDocContextPrecision()),
        new MetricDef("Faithfulness", true, m -> m.avgFaithfulness()),
        new MetricDef("Response Relevancy", true, m -> m.avgResponseRelevancy()),
        new MetricDef("Negative Rejection", true, m -> m.negativeRejectionRate()),
        new MetricDef("Injection Block（总体）", true, m -> m.injectionBlockRate()),
        new MetricDef("Injection Block（门禁子集）", true, m -> m.injectionGateBlockRate()),
        new MetricDef("Answer Correctness", true, m -> m.phase5().avgAnswerCorrectness()),
        new MetricDef("Citation Pass Rate", true, m -> m.phase5().citationPassRate()),
        new MetricDef("Hallucination Rate", false, m -> m.phase5().avgHallucinationRate()),
        new MetricDef("Noise Consistency", true, m -> m.phase5().noiseConsistencyRate()));

    /** 名义判定翻转维度（双侧均有读数且不同才计翻转；单侧缺失属配置差异，配置面已 ⚠） */
    record VerdictDim(String label, Function<EvalSnapshot.CaseScores, String> get) {}

    static final List<VerdictDim> VERDICT_DIMENSIONS = List.of(
        new VerdictDim("负向拒答", EvalSnapshot.CaseScores::rejectionVerdict),
        new VerdictDim("注入拦截", EvalSnapshot.CaseScores::injectionVerdict),
        new VerdictDim("注入拦截（L1+L2）", EvalSnapshot.CaseScores::l2InjectionVerdict),
        new VerdictDim("引用溯源", EvalSnapshot.CaseScores::citationVerdict),
        new VerdictDim("噪声鲁棒", EvalSnapshot.CaseScores::noiseVerdict));

    /** 逐例数值指标（双侧均非 null 且非 NaN 才比；|Δ| 超容忍带计入异动） */
    record CaseMetric(String label, Function<EvalSnapshot.CaseScores, Double> get) {}

    static final List<CaseMetric> CASE_METRICS = List.of(
        new CaseMetric("Recall", EvalSnapshot.CaseScores::recall),
        new CaseMetric("MRR", EvalSnapshot.CaseScores::mrr),
        new CaseMetric("ContextPrecision", EvalSnapshot.CaseScores::contextPrecision),
        new CaseMetric("DocRecall", EvalSnapshot.CaseScores::docRecall),
        new CaseMetric("DocMRR", EvalSnapshot.CaseScores::docMrr),
        new CaseMetric("DocContextPrecision", EvalSnapshot.CaseScores::docContextPrecision),
        new CaseMetric("Faithfulness", EvalSnapshot.CaseScores::faithfulness),
        new CaseMetric("ResponseRelevancy", EvalSnapshot.CaseScores::responseRelevancy),
        new CaseMetric("AnswerCorrectness", EvalSnapshot.CaseScores::answerCorrectness),
        new CaseMetric("CitationResolvableRate", EvalSnapshot.CaseScores::citationResolvableRate),
        new CaseMetric("HallucinationRate", EvalSnapshot.CaseScores::hallucinationRate));

    static String buildReport(EvalSnapshot a, EvalSnapshot b, String labelA, String labelB, double tolerance) {
        String ls = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append("═══ Eval A/B 差异报表（簇② 5.9 批3） ═══").append(ls);
        sb.append(String.format(Locale.ROOT,
            "对比容忍带：±%.3f（eval.thresholds.regression；超带判 IMPROVED/REGRESSED，带内判 STABLE）%n",
            tolerance));
        sb.append(ls);

        appendAnchorSection(sb, a, b, labelA, labelB);
        appendConfigSection(sb, a.runConfig(), b.runConfig());
        int[] verdictCounts = appendAggregateSection(sb, a.aggregates(), b.aggregates(), tolerance);
        appendCategorySection(sb, a, b);
        Map<String, EvalSnapshot.CaseScores> casesA = byId(a);
        Map<String, EvalSnapshot.CaseScores> casesB = byId(b);
        appendVerdictFlipSection(sb, casesA, casesB);
        appendMovedSection(sb, casesA, casesB, tolerance);
        appendAnswerChangeSection(sb, casesA, casesB);
        appendCaseSetSection(sb, casesA, casesB);

        sb.append(ls).append("总体：可对比聚合指标 ").append(verdictCounts[0]).append(" 项 IMPROVED / ")
            .append(verdictCounts[1]).append(" 项 REGRESSED / ").append(verdictCounts[2])
            .append(" 项 STABLE（样本缺失项不计）").append(ls);
        sb.append("（观察面报表，不入门禁；A/B 结论以单变量为前提，配置失配项见核验小节）");
        return sb.toString();
    }

    private static void appendAnchorSection(StringBuilder sb, EvalSnapshot a, EvalSnapshot b,
                                            String labelA, String labelB) {
        String ls = System.lineSeparator();
        sb.append("── 运行锚点 ──").append(ls);
        sb.append("| | Run A（").append(labelA).append("） | Run B（").append(labelB).append("） |").append(ls);
        sb.append("|---|---|---|").append(ls);
        sb.append("| git commit | ").append(anchorCell(a.anchor()))
            .append(" | ").append(anchorCell(b.anchor())).append(" |").append(ls);
        sb.append("| 提交时间 | ").append(a.anchor().commitTime())
            .append(" | ").append(b.anchor().commitTime()).append(" |").append(ls);
        sb.append("| 运行时刻 | ").append(a.anchor().runAt())
            .append(" | ").append(b.anchor().runAt()).append(" |").append(ls);
        sb.append("| 用例规模 | ").append(scaleCell(a.aggregates()))
            .append(" | ").append(scaleCell(b.aggregates())).append(" |").append(ls);
        sb.append(ls);
    }

    private static String anchorCell(GitAnchor anchor) {
        if (!anchor.resolved()) {
            return "UNKNOWN（非 git 工作区）";
        }
        return anchor.commitShort() + (anchor.dirty() ? "（工作区脏 ⚠）" : "（工作区干净）");
    }

    private static String scaleCell(EvalSnapshot.Aggregates agg) {
        return String.format("总 %d / 检索 %d / 生成 %d / 负向 %d / 注入 %d",
            agg.totalPairs(), agg.retrievalEvaluated(), agg.generationEvaluated(),
            agg.negativeEvaluated(), agg.injectionEvaluated());
    }

    /** 运行配置一致性核验：单变量 A/B 假设——除被验证变量外配置应一致，失配即混杂因素 ⚠ */
    private static void appendConfigSection(StringBuilder sb, EvalSnapshot.RunConfig a, EvalSnapshot.RunConfig b) {
        String ls = System.lineSeparator();
        List<String[]> rows = List.of(
            new String[]{"检索探针", a.probe(), b.probe()},
            new String[]{"每类抽样", String.valueOf(a.sampleSize()), String.valueOf(b.sampleSize())},
            new String[]{"Top-K", String.valueOf(a.topK()), String.valueOf(b.topK())},
            new String[]{"检索-only", String.valueOf(a.retrievalOnly()), String.valueOf(b.retrievalOnly())},
            new String[]{"Judge 模型", a.judgeModel(), b.judgeModel()},
            new String[]{"Judge 温度", String.valueOf(a.judgeTemperature()), String.valueOf(b.judgeTemperature())},
            new String[]{"Judge 思考", String.valueOf(a.judgeEnableThinking()), String.valueOf(b.judgeEnableThinking())},
            new String[]{"Phase5 指标", String.valueOf(a.phase5Enabled()), String.valueOf(b.phase5Enabled())},
            new String[]{"NRob 抽样", String.valueOf(a.noiseSampleSize()), String.valueOf(b.noiseSampleSize())},
            new String[]{"护栏 L2", String.valueOf(a.guardrailL2Enabled()), String.valueOf(b.guardrailL2Enabled())});
        sb.append("── 运行配置一致性核验 ──").append(ls);
        int mismatches = 0;
        for (String[] row : rows) {
            boolean match = java.util.Objects.equals(row[1], row[2]);
            if (!match) {
                mismatches++;
            }
            sb.append("- ").append(row[0]).append("：A=").append(row[1])
                .append(" / B=").append(row[2]).append(match ? "" : "  ⚠ 失配").append(ls);
        }
        sb.append(mismatches == 0
            ? "配置一致——单变量 A/B 假设成立（差异归于被验证变量）"
            : "⚠ " + mismatches + " 项配置失配——存在混杂因素，差异归因须谨慎").append(ls).append(ls);
    }

    /** 聚合指标对照表；返回 [IMPROVED, REGRESSED, STABLE] 计数（样本缺失不计） */
    private static int[] appendAggregateSection(StringBuilder sb, EvalSnapshot.Aggregates a,
                                                EvalSnapshot.Aggregates b, double tolerance) {
        String ls = System.lineSeparator();
        sb.append("── 聚合指标对照 ──").append(ls);
        sb.append("| 指标 | A | B | Δ（B−A） | 判定 |").append(ls);
        sb.append("|---|---|---|---|---|").append(ls);
        int[] counts = new int[3];
        for (MetricDef metric : AGGREGATE_METRICS) {
            double va = metric.value().applyAsDouble(a);
            double vb = metric.value().applyAsDouble(b);
            String verdict = verdict(va, vb, metric.higherBetter(), tolerance);
            switch (verdict) {
                case "IMPROVED" -> counts[0]++;
                case "REGRESSED" -> counts[1]++;
                case "STABLE" -> counts[2]++;
                default -> { /* 样本缺失不计 */ }
            }
            sb.append("| ").append(metric.label())
                .append(" | ").append(fmt(va))
                .append(" | ").append(fmt(vb))
                .append(" | ").append(delta(va, vb))
                .append(" | ").append(verdict).append(" |").append(ls);
        }
        sb.append(ls);
        return counts;
    }

    private static String verdict(double va, double vb, boolean higherBetter, double tolerance) {
        if (Double.isNaN(va) || Double.isNaN(vb)) {
            return "样本缺失";
        }
        double d = vb - va;
        boolean improved = higherBetter ? d > tolerance : d < -tolerance;
        boolean regressed = higherBetter ? d < -tolerance : d > tolerance;
        return improved ? "IMPROVED" : regressed ? "REGRESSED" : "STABLE";
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String delta(double va, double vb) {
        return Double.isNaN(va) || Double.isNaN(vb)
            ? "—" : String.format(Locale.ROOT, "%+.3f", vb - va);
    }

    /** 生成侧分类分解对照（Faithfulness/Relevancy 均值，逐分类样本数） */
    private static void appendCategorySection(StringBuilder sb, EvalSnapshot a, EvalSnapshot b) {
        String ls = System.lineSeparator();
        Map<String, double[]> statA = categoryStats(a);
        Map<String, double[]> statB = categoryStats(b);
        if (statA.isEmpty() && statB.isEmpty()) {
            return;
        }
        sb.append("── 生成侧分类分解（F=Faithfulness / RR=Response Relevancy）──").append(ls);
        sb.append("| 分类 | n(A/B) | F(A) | F(B) | ΔF | RR(A) | RR(B) | ΔRR |").append(ls);
        sb.append("|---|---|---|---|---|---|---|---|").append(ls);
        TreeSet<String> categories = new TreeSet<>();
        categories.addAll(statA.keySet());
        categories.addAll(statB.keySet());
        for (String category : categories) {
            double[] sa = statA.get(category);
            double[] sbv = statB.get(category);
            sb.append("| ").append(category)
                .append(" | ").append(count(sa)).append("/").append(count(sbv))
                .append(" | ").append(mean(sa, 1)).append(" | ").append(mean(sbv, 1))
                .append(" | ").append(delta(meanValue(sa, 1), meanValue(sbv, 1)))
                .append(" | ").append(mean(sa, 3)).append(" | ").append(mean(sbv, 3))
                .append(" | ").append(delta(meanValue(sa, 3), meanValue(sbv, 3)))
                .append(" |").append(ls);
        }
        sb.append(ls);
    }

    /** 分类 → [nF, sumF, nRR, sumRR]（faithfulness/relevancy 各自非 null 才计） */
    private static Map<String, double[]> categoryStats(EvalSnapshot snapshot) {
        Map<String, double[]> stats = new TreeMap<>();
        for (EvalSnapshot.CaseScores c : snapshot.cases()) {
            if (c.faithfulness() == null && c.responseRelevancy() == null) {
                continue;
            }
            double[] stat = stats.computeIfAbsent(c.category(), k -> new double[4]);
            if (c.faithfulness() != null) {
                stat[0]++;
                stat[1] += c.faithfulness();
            }
            if (c.responseRelevancy() != null) {
                stat[2]++;
                stat[3] += c.responseRelevancy();
            }
        }
        return stats;
    }

    private static String count(double[] stat) {
        return stat == null ? "0" : String.valueOf((int) stat[0]);
    }

    private static double meanValue(double[] stat, int sumIndex) {
        if (stat == null || stat[sumIndex - 1] == 0) {
            return Double.NaN;
        }
        return stat[sumIndex] / stat[sumIndex - 1];
    }

    private static String mean(double[] stat, int sumIndex) {
        return fmt(meanValue(stat, sumIndex));
    }

    private static void appendVerdictFlipSection(StringBuilder sb,
                                                 Map<String, EvalSnapshot.CaseScores> casesA,
                                                 Map<String, EvalSnapshot.CaseScores> casesB) {
        String ls = System.lineSeparator();
        sb.append("── 判定翻转（名义 verdict 逐例）──").append(ls);
        int flips = 0;
        for (Map.Entry<String, EvalSnapshot.CaseScores> e : casesA.entrySet()) {
            EvalSnapshot.CaseScores cb = casesB.get(e.getKey());
            if (cb == null) {
                continue;
            }
            for (VerdictDim dim : VERDICT_DIMENSIONS) {
                String va = dim.get().apply(e.getValue());
                String vb = dim.get().apply(cb);
                if (va != null && vb != null && !va.equals(vb)) {
                    sb.append("- ").append(e.getKey()).append(" / ").append(dim.label())
                        .append("：").append(va).append(" → ").append(vb).append(ls);
                    flips++;
                }
            }
        }
        sb.append(flips == 0 ? "（无）" + ls : ls);
    }

    private static void appendMovedSection(StringBuilder sb,
                                           Map<String, EvalSnapshot.CaseScores> casesA,
                                           Map<String, EvalSnapshot.CaseScores> casesB,
                                           double tolerance) {
        String ls = System.lineSeparator();
        sb.append(String.format(Locale.ROOT, "── 数值异动（|Δ| > %.3f，逐例）──%n", tolerance));
        int moved = 0;
        for (Map.Entry<String, EvalSnapshot.CaseScores> e : casesA.entrySet()) {
            EvalSnapshot.CaseScores cb = casesB.get(e.getKey());
            if (cb == null) {
                continue;
            }
            List<String> moves = new ArrayList<>();
            for (CaseMetric metric : CASE_METRICS) {
                Double va = metric.get().apply(e.getValue());
                Double vb = metric.get().apply(cb);
                if (va == null || vb == null || Double.isNaN(va) || Double.isNaN(vb)) {
                    continue;
                }
                if (Math.abs(vb - va) > tolerance) {
                    moves.add(String.format(Locale.ROOT, "%s %.3f→%.3f（Δ%+.3f）",
                        metric.label(), va, vb, vb - va));
                }
            }
            if (!moves.isEmpty()) {
                sb.append("- ").append(e.getKey()).append("：").append(String.join("；", moves)).append(ls);
                moved++;
            }
        }
        sb.append(moved == 0 ? "（无）" + ls : ls);
    }

    /** 答案变化（SHA-256 指纹比对——内容盲：只报「是否变了」，不回显正文） */
    private static void appendAnswerChangeSection(StringBuilder sb,
                                                  Map<String, EvalSnapshot.CaseScores> casesA,
                                                  Map<String, EvalSnapshot.CaseScores> casesB) {
        String ls = System.lineSeparator();
        sb.append("── 答案变化（SHA-256 指纹，内容盲）──").append(ls);
        List<String> changed = new ArrayList<>();
        List<String> availabilityChanged = new ArrayList<>();
        for (Map.Entry<String, EvalSnapshot.CaseScores> e : casesA.entrySet()) {
            EvalSnapshot.CaseScores cb = casesB.get(e.getKey());
            if (cb == null) {
                continue;
            }
            String ha = e.getValue().answerSha256();
            String hb = cb.answerSha256();
            if (ha == null || hb == null) {
                if (ha == null != (hb == null)) {
                    availabilityChanged.add(e.getKey());
                }
                continue;
            }
            if (!ha.equals(hb)) {
                changed.add(e.getKey());
            }
        }
        sb.append(changed.isEmpty() ? "答案指纹全部一致" + ls
            : "答案变化 " + changed.size() + " 例：" + String.join("、", changed) + ls);
        if (!availabilityChanged.isEmpty()) {
            sb.append("答案可得性变化（生成失败↔成功）").append(availabilityChanged.size())
                .append(" 例：").append(String.join("、", availabilityChanged)).append(ls);
        }
        sb.append(ls);
    }

    private static void appendCaseSetSection(StringBuilder sb,
                                             Map<String, EvalSnapshot.CaseScores> casesA,
                                             Map<String, EvalSnapshot.CaseScores> casesB) {
        String ls = System.lineSeparator();
        sb.append("── 用例集差异 ──").append(ls);
        List<String> onlyA = casesA.keySet().stream().filter(id -> !casesB.containsKey(id)).sorted().toList();
        List<String> onlyB = casesB.keySet().stream().filter(id -> !casesA.containsKey(id)).sorted().toList();
        if (onlyA.isEmpty() && onlyB.isEmpty()) {
            sb.append("用例集一致（共 ").append(casesA.size()).append(" 例）").append(ls);
            return;
        }
        sb.append("⚠ 用例集不一致（golden 语料漂移——差异含数据集变化成分）：").append(ls);
        if (!onlyA.isEmpty()) {
            sb.append("- 仅 A 有：").append(String.join("、", onlyA)).append(ls);
        }
        if (!onlyB.isEmpty()) {
            sb.append("- 仅 B 有：").append(String.join("、", onlyB)).append(ls);
        }
    }

    private static Map<String, EvalSnapshot.CaseScores> byId(EvalSnapshot snapshot) {
        Map<String, EvalSnapshot.CaseScores> map = new LinkedHashMap<>();
        for (EvalSnapshot.CaseScores c : snapshot.cases()) {
            map.put(c.id(), c);
        }
        return map;
    }
}
