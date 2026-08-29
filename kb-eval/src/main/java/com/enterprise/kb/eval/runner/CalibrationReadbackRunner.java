package com.enterprise.kb.eval.runner;

import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.metric.CohensKappa;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 人类校准 κ 回读器（簇② 5.8 批2，16 章 §16.2）
 *
 * <p>用法：打分表（{@code judge-agreement-sheet.csv}）经两位标注人独立回填
 * human_a / human_b 列后——
 * {@code mvn spring-boot:run -pl kb-eval -Dspring-boot.run.arguments=--eval.calibration-readback=target/judge-agreement-sheet.csv}
 *
 * <p>逐维计算 Cohen's κ 三对（Judge×A / Judge×B / A×B——A×B 为标注人间一致性，
 * 校准质量的先行信号：人审都不一致则 Judge 无从校准），对照
 * {@code eval.calibration.kappa-target}（缺省 0.80）逐维判 PASS/FAIL，
 * 报告落 {@code target/calibration-kappa-report{-label}.txt}。
 *
 * <p>维度标度：faithfulness / answer_correctness 为 1-5 序数（二次加权 κ +
 * E1 口径 |差|≤1 一致率并行报告）；citation_attribution（NO_CITATION 归并
 * NOT_SUPPORTED——未发出引用判负，与聚合语义一致）/ hallucination（Judge
 * 比率 >0 → HAS）/ noise_robustness 为名义二分类（名义 κ）。
 */
@Slf4j
@Component
public class CalibrationReadbackRunner implements ApplicationRunner {

    /** 报告维度顺序（固定，跨次复跑可比） */
    static final List<String> DIMENSIONS = List.of(
        "faithfulness", "answer_correctness", "citation_attribution", "hallucination", "noise_robustness");

    private final EvalProperties props;

    public CalibrationReadbackRunner(EvalProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String path = args.getOptionValues("eval.calibration-readback") == null ? null
            : args.getOptionValues("eval.calibration-readback").stream().findFirst().orElse(null);
        if (path == null || path.isBlank()) {
            return;
        }
        String content;
        try {
            content = Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new IllegalStateException("校准打分表读取失败：" + path, e);
        }
        String report = buildReport(parseCsv(content), props.getCalibration().getKappaTarget(),
            props.getCalibration().getObservationDimensions());
        System.out.println(report);
        log.info("\n{}", report);
        try {
            String label = props.getRunLabel() == null ? "" : props.getRunLabel().trim();
            String fileName = label.isEmpty() ? "calibration-kappa-report.txt"
                : "calibration-kappa-report-" + label + ".txt";
            Path out = Path.of("target", fileName);
            Files.createDirectories(out.getParent());
            Files.writeString(out, report + System.lineSeparator());
            log.info("校准 κ 报告已写入: {}", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("校准 κ 报告落盘失败: {}", e.getMessage());
        }
    }

    /** 打分表行：长表，每行 = 用例 × 维度 */
    record Row(String caseId, String category, String dimension,
               String judgeValue, String humanA, String humanB) {}

    /**
     * 打分表解析：校验表头契约，空行跳过；列数不符即时报错（定位到行号，
     * 防整表静默错位）。人工列留空 = 未标注，由统计层按维度过滤。
     */
    static List<Row> parseCsv(String content) {
        List<Row> rows = new ArrayList<>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",", -1);
            if (i == 0) {
                if (f.length != 6 || !f[0].equals("case_id") || !f[2].equals("dimension")
                        || !f[3].equals("judge_value") || !f[4].equals("human_a") || !f[5].equals("human_b")) {
                    throw new IllegalArgumentException(
                        "打分表表头契约不符（期望 case_id,category,dimension,judge_value,human_a,human_b）：" + line);
                }
                continue;
            }
            if (f.length != 6) {
                throw new IllegalArgumentException("打分表第 " + (i + 1) + " 行列数不符（期望 6 列）：" + line);
            }
            rows.add(new Row(f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip(), f[5].strip()));
        }
        return rows;
    }

    /** 观察带维度缺省集（与 {@code eval.calibration.observation-dimensions} 默认值一致） */
    static final List<String> DEFAULT_OBSERVATION_DIMENSIONS = List.of("noise_robustness");

    /** 兼容入口：观察带维度取缺省集（noise_robustness，M3 裁决，16 章 v2.79） */
    static String buildReport(List<Row> rows, double kappaTarget) {
        return buildReport(rows, kappaTarget, DEFAULT_OBSERVATION_DIMENSIONS);
    }

    /**
     * κ 报告构建：逐维三对 κ + 评分类维度的 |差|≤1 一致率；无样本维度标注
     * 「待样本」不计成败。总体判定 = 观察带外的有样本维度全部双 κ 达标。
     *
     * <p>观察带维度（素材呈现面并议 M3 裁决，16 章 v2.79）：κ 照算报告，
     * verdict 记「观察」，不计总体成败——n=33 患病率偏差 + Judge 单方向误报面
     * 使 NRob κ 不构成可信门禁信号；复启门禁 = 配置清空观察集。
     */
    static String buildReport(List<Row> rows, double kappaTarget, List<String> observationDimensions) {
        List<String> observation = observationDimensions == null ? List.of() : observationDimensions;
        Map<String, List<Row>> byDimension = new LinkedHashMap<>();
        for (String dim : DIMENSIONS) {
            byDimension.put(dim, new ArrayList<>());
        }
        for (Row r : rows) {
            List<Row> bucket = byDimension.get(r.dimension());
            if (bucket == null) {
                throw new IllegalArgumentException("未知维度：" + r.dimension() + "（用例 " + r.caseId() + "）");
            }
            bucket.add(r);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("═══ 人类校准 κ 报告（簇② 批2） ═══").append(System.lineSeparator());
        sb.append(String.format(Locale.ROOT, "κ 目标：%.2f（观察带接入门禁前置判据）%n", kappaTarget));
        sb.append(String.format("%-22s %7s %12s %12s %10s %14s  %s%n",
            "dimension", "n(A/B)", "κ(Judge,A)", "κ(Judge,B)", "κ(A,B)", "|diff|≤1(A/B)", "verdict"));
        boolean allPass = true;
        int sampledDimensions = 0;
        for (String dim : DIMENSIONS) {
            DimensionStat stat = dimensionStat(dim, byDimension.get(dim));
            if (stat.nA() == 0 && stat.nB() == 0) {
                sb.append(String.format("%-22s %7s%n", dim, "待样本"));
                continue;
            }
            sampledDimensions++;
            boolean pass = pass(stat.kappaJudgeA(), kappaTarget) && pass(stat.kappaJudgeB(), kappaTarget);
            boolean observed = observation.contains(dim);
            if (!observed) {
                allPass &= pass;
            }
            sb.append(String.format(Locale.ROOT, "%-22s %3d/%-3d %12s %12s %10s %14s  %s%n",
                dim, stat.nA(), stat.nB(),
                fmtKappa(stat.kappaJudgeA()), fmtKappa(stat.kappaJudgeB()), fmtKappa(stat.kappaAB()),
                stat.withinOneA() == null || stat.withinOneB() == null ? "—"
                    : String.format(Locale.ROOT, "%.0f%%/%.0f%%",
                        stat.withinOneA() * 100, stat.withinOneB() * 100),
                observed ? "观察" : pass ? "PASS" : "FAIL"));
        }
        sb.append(System.lineSeparator());
        sb.append("总体判定：").append(sampledDimensions == 0 ? "无已标注维度" : allPass ? "PASS" : "FAIL")
            .append(System.lineSeparator());
        sb.append("（PASS 语义 = 观察带外全部有样本维度的 Judge×A 与 Judge×B κ 均 ≥ 目标；")
            .append("观察带维度只报告不计成败（M3 裁决，16 章 v2.79）；")
            .append("κ(A,B) 为标注人间一致性，人审失配时优先复核标注口径）").append(System.lineSeparator());
        return sb.toString();
    }

    private static boolean pass(Double kappa, double target) {
        return kappa != null && !kappa.isNaN() && kappa >= target;
    }

    private static String fmtKappa(Double kappa) {
        return kappa == null || kappa.isNaN() ? "未定" : String.format(Locale.ROOT, "%.3f", kappa);
    }

    /** 单维统计：Judge×A / Judge×B 各按有效标注对独立配对（单边标注亦计入其对） */
    record DimensionStat(int nA, int nB,
                         Double kappaJudgeA, Double kappaJudgeB, Double kappaAB,
                         Double withinOneA, Double withinOneB) {}

    private static DimensionStat dimensionStat(String dimension, List<Row> rows) {
        boolean rating = "faithfulness".equals(dimension) || "answer_correctness".equals(dimension);
        if (rating) {
            List<Integer> judgeA = new ArrayList<>();
            List<Integer> humanA = new ArrayList<>();
            List<Integer> judgeB = new ArrayList<>();
            List<Integer> humanB = new ArrayList<>();
            List<Integer> aOnly = new ArrayList<>();
            List<Integer> bOnly = new ArrayList<>();
            for (Row row : rows) {
                Integer j = parseRating(row.judgeValue());
                Integer va = parseRating(row.humanA());
                Integer vb = parseRating(row.humanB());
                if (j == null) {
                    continue;
                }
                if (va != null) {
                    judgeA.add(j);
                    humanA.add(va);
                }
                if (vb != null) {
                    judgeB.add(j);
                    humanB.add(vb);
                }
                if (va != null && vb != null) {
                    aOnly.add(va);
                    bOnly.add(vb);
                }
            }
            return new DimensionStat(humanA.size(), humanB.size(),
                humanA.isEmpty() ? null : CohensKappa.weightedQuadratic(judgeA, humanA, 5),
                humanB.isEmpty() ? null : CohensKappa.weightedQuadratic(judgeB, humanB, 5),
                aOnly.isEmpty() ? null : CohensKappa.nominal(intStrings(aOnly), intStrings(bOnly)),
                humanA.isEmpty() ? null : CohensKappa.withinOneAgreement(judgeA, humanA),
                humanB.isEmpty() ? null : CohensKappa.withinOneAgreement(judgeB, humanB));
        }
        List<String> judgeA = new ArrayList<>();
        List<String> humanA = new ArrayList<>();
        List<String> judgeB = new ArrayList<>();
        List<String> humanB = new ArrayList<>();
        List<String> aOnly = new ArrayList<>();
        List<String> bOnly = new ArrayList<>();
        for (Row row : rows) {
            String j = normalizeNominalJudge(dimension, row.judgeValue());
            String va = normalizeNominal(dimension, row.humanA());
            String vb = normalizeNominal(dimension, row.humanB());
            if (j == null) {
                continue;
            }
            if (va != null) {
                judgeA.add(j);
                humanA.add(va);
            }
            if (vb != null) {
                judgeB.add(j);
                humanB.add(vb);
            }
            if (va != null && vb != null) {
                aOnly.add(va);
                bOnly.add(vb);
            }
        }
        return new DimensionStat(humanA.size(), humanB.size(),
            judgeA.isEmpty() ? null : CohensKappa.nominal(judgeA, humanA),
            judgeB.isEmpty() ? null : CohensKappa.nominal(judgeB, humanB),
            aOnly.isEmpty() ? null : CohensKappa.nominal(aOnly, bOnly),
            null, null);
    }

    private static List<String> intStrings(List<Integer> values) {
        return values.stream().map(String::valueOf).toList();
    }

    /**
     * 名义维度人工值归一：大小写统一；无法识别的值视为未标注（null）。
     * CA 的 NO_CITATION 归并 NOT_SUPPORTED（未发出引用判负，与聚合/门禁语义
     * 一致——人审口径同：无引用即 NOT_SUPPORTED）；HR 人工填 YES/NO
     * （≥1 条无依据声明即 YES）。
     */
    static String normalizeNominal(String dimension, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        return switch (dimension) {
            case "citation_attribution" -> {
                if ("NO_CITATION".equals(v) || "NOT_SUPPORTED".equals(v)) {
                    yield "NOT_SUPPORTED";
                }
                yield "SUPPORTED".equals(v) ? "SUPPORTED" : null;
            }
            case "hallucination" -> switch (v) {
                case "YES", "HAS" -> "HAS";
                case "NO", "NONE" -> "NONE";
                default -> null;
            };
            case "noise_robustness" ->
                "CONSISTENT".equals(v) || "DRIFTED".equals(v) ? v : null;
            default -> v;
        };
    }

    /** Judge 值归一：名义维度按人工口径归一；hallucination 特例 = 原始比率二值化（>0 → HAS） */
    static String normalizeNominalJudge(String dimension, String value) {
        if ("hallucination".equals(dimension)) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(value.strip()) > 0 ? "HAS" : "NONE";
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return normalizeNominal(dimension, value);
    }

    /** 1-5 评分解析：空白或越界视为未标注（null） */
    private static Integer parseRating(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int v = (int) Math.round(Double.parseDouble(value.strip()));
            return v >= 1 && v <= 5 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
