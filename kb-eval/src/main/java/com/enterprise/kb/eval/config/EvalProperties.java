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

    /** Top-K 召回的 K 值，与检索主链路 Constants.DEFAULT_TOP_K 保持一致 */
    private int topK = 5;

    /** 每个分类最多抽样条数，0 = 全量（CI 快跑用） */
    private int sampleSize = 0;

    /**
     * 用例并行度（虚拟线程并发执行）。单用例含 1 次生成 + 至多 2 次 Judge 的串联 LLM 调用，
     * 串行 74 条约 70+ 分钟，并行后约 1/N。1 = 传统串行；过高可能触发 LLM API 限流。
     */
    private int concurrency = 5;

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
    }

    @Getter
    @Setter
    public static class Thresholds {
        private double topKRecall = 0.85;
        private double mrr = 0.70;
        private double faithfulness = 4.0;
        private double negativeRejection = 0.85;
        /** 较基线回归容忍度（预留，基线对比机制 Phase 5 落地） */
        private double regression = 0.03;
    }
}
