package com.enterprise.kb.eval;

import java.util.List;

/**
 * kb-eval 模块内常量——judge 裁决值 / 报告维度名 / 探针模式名
 *
 * <p>模块内跨类协议性字面量（打分表与报告 ↔ 门禁统计 ↔ 配置缺省共享），
 * 不入 kb-commons 全局 Constants（非跨模块协议，§6.3 边界）。引用域裁决值
 * （SUPPORTED 等）以 {@link com.enterprise.kb.eval.metric.CitationMetrics}
 * 既有常量为单一事实源，本类不重复定义；hallucination 的 HAS/NONE 二值
 * 归一仅 CalibrationReadbackRunner 单类消费，保留类内字面。
 *
 * <p>探针模式 {@code vector} 与检索路名同字面但属不同值域——EvalRunner
 * 沿用 {@code Constants.Retrieval.ROUTE_VECTOR}，本类不重复定义。
 */
public final class EvalConstants {

    private EvalConstants() {}

    // ── noise_robustness 裁决（Judge 判定回答与注入噪声语境一致 / 漂移）──
    public static final String VERDICT_CONSISTENT = "CONSISTENT";
    public static final String VERDICT_DRIFTED = "DRIFTED";

    // ── 报告维度名（Judge 评分维度 / 打分表列 / csvRow 输出 / 归一化分流）──
    public static final String DIM_FAITHFULNESS = "faithfulness";
    public static final String DIM_ANSWER_CORRECTNESS = "answer_correctness";
    public static final String DIM_CITATION_ATTRIBUTION = "citation_attribution";
    public static final String DIM_HALLUCINATION = "hallucination";
    public static final String DIM_NOISE_ROBUSTNESS = "noise_robustness";

    /** 报告维度全量顺序（固定，跨次复跑可比；打分表列序一致） */
    public static final List<String> DIMENSIONS = List.of(
        DIM_FAITHFULNESS, DIM_ANSWER_CORRECTNESS, DIM_CITATION_ATTRIBUTION,
        DIM_HALLUCINATION, DIM_NOISE_ROBUSTNESS);

    /** 观察带维度缺省（κ 照算报告但不计总体成败；M3 裁决单源，防双源漂移） */
    public static final List<String> DEFAULT_OBSERVATION_DIMENSIONS = List.of(DIM_NOISE_ROBUSTNESS);

    // ── 探针模式名（eval.probe 配置值 / probe.name() 注册名 / selectProbe 分流）──
    public static final String PROBE_AUTO = "auto";
    public static final String PROBE_HYBRID = "hybrid";
    public static final String PROBE_CHAIN = "chain";
    public static final String PROBE_VECTOR_SINGLE = "vector-single";
}
