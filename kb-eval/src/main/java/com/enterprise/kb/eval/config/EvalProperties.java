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
