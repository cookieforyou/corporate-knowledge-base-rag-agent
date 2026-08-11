package com.enterprise.kb.eval.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评估配置 —— 前缀 eval，阈值表对应设计文档第十六章 16.4 CI 门禁阈值表
 *
 * <p>Phase 2 为「建基线期」，阈值从宽；Phase 5 校准后收紧（见 16.4）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /**
     * Top-K 召回的 K 值。与被测链路 rag.retrieval.top-k 同源（yml 均绑定
     * RAG_RETRIEVAL_TOP_K，簇① A3）——独立配置存在漂移风险，度量 K 与链路产出 K
     * 不一致时 Recall@K/MRR 失真。
     */
    private int topK = 5;

    /** 每个分类最多抽样条数，0 = 全量（CI 快跑用） */
    private int sampleSize = 0;

    /**
     * 用例并行度（虚拟线程并发执行）。单用例含 1 次生成 + 至多 2 次 Judge 的串联 LLM 调用，
     * 串行 102 条约 70+ 分钟，并行后约 1/N。1 = 传统串行；过高可能触发 LLM API 限流。
     */
    private int concurrency = 5;

    /**
     * 运行标签（簇④ E1）：非空时报告落盘文件名变为 eval-report-{label}.txt——
     * Judge 校准复跑（thinking 开/关）与 A/B 基线快照各留独立文件，避免互相覆盖。
     * 空 = 默认 eval-report.txt。
     */
    private String runLabel = "";

    /**
     * 人工-Judge 一致率抽样条数（簇④ E1，0 = 关闭）。非零时全量评估后按分类分层抽样，
     * 落盘 target/judge-agreement-sheet.md 供人工打分，度量 Judge 可信度
     * （一致口径 |人工−Judge|≤1，目标一致率 ≥85%，Phase 5.8 人类校准前置）。
     */
    private int judgeAgreementSample = 0;

    /**
     * 检索-only 模式：只跑检索取数 + 检索侧指标（Recall/MRR/Context Precision），
     * 跳过被测生成与 Judge——语料标注核验/检索回归的秒级快跑通道。
     * 生成侧/负向指标该模式下无样本，聚合与门禁自动跳过；不依赖 DASHSCOPE_API_KEY。
     */
    private boolean retrievalOnly = false;

    /** 检索探针选择：auto（min order，混合探针就位后默认 hybrid）| vector | hybrid，A/B 基线对比用 */
    private String probe = "auto";

    private final Ci ci = new Ci();
    private final Judge judge = new Judge();
    private final Thresholds thresholds = new Thresholds();

    @Getter
    @Setter
    public static class Ci {
        /** ci profile 下置 true，启动即跑评估并执行门禁 */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    public static class Judge {
        /** Judge 模型端点（默认百炼 OpenAI 兼容端点，与被测 DeepSeek 形成跨厂商评判） */
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey;
        /** 16.3：Judge 模型须与被测模型隔离（被测 DeepSeek V4，Judge 默认 qwen3.7-plus） */
        private String model = "qwen3.7-plus";
        private Double temperature = 0.0;
        /**
         * qwen3.5/3.6/3.7 商业版默认开思考模式（enable_thinking=true，官方文档实证）——
         * 评估期每条用例多次 Judge 调用，思维链大幅拉长耗时与 token，默认显式关闭。
         * 簇④ E1 校准口径：thinking 开/关两形态漂移须经复跑定档（EVAL_JUDGE_ENABLE_THINKING
         * 切换复跑，run-label 各留快照），基线口径定档前不得跨形态对比分数。
         */
        private boolean enableThinking = false;
    }

    @Getter
    @Setter
    public static class Thresholds {
        private double topKRecall = 0.85;
        private double mrr = 0.70;
        private double faithfulness = 4.0;
        /**
         * Faithfulness 噪声容忍带（簇④ E1）：Judge 分数本身带噪声，均值落在
         * [faithfulness − tolerance, faithfulness) 区间视为噪声带——门禁 WARN 不 FAIL，
         * 低于区间下沿才判真实击穿。规避「4.093 贴线」单次抖动误杀。
         */
        private double faithfulnessTolerance = 0.05;
        /**
         * 分类均值地板（簇④ E1「单维不崩」）：整体均值可能被大类拉高而掩盖单一分类崩盘，
         * 任一正向分类 Faithfulness 均值低于此地板即门禁失败。
         */
        private double faithfulnessCategoryFloor = 3.5;
        /** 地板生效的最小样本数：分类样本不足时跳过地板检查（小样本均值噪声过大） */
        private int faithfulnessCategoryMinSamples = 3;
        private double negativeRejection = 0.85;
        /** 较基线回归容忍度（预留，基线对比机制 Phase 5 落地） */
        private double regression = 0.03;
    }
}
