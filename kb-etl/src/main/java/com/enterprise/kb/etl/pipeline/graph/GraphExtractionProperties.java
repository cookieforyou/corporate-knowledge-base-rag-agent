package com.enterprise.kb.etl.pipeline.graph;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 图谱抽取配置族（簇④ 5.1，{@code rag.graph.extraction.*}，缺省值 = 保守形态）。
 *
 * <p>与图谱域全族同条件装配（{@code rag.graph.enabled=true}）——关闭态 Bean 缺位，
 * 链形态零变化。缺省经 YAML 占位消费 {@code DASHSCOPE_API_KEY}（备用/评估链同源密钥，
 * 见 application-ai.yml），模型 = 百炼 qwen3.7-plus（抽取是低价档结构化调用，
 * 不经主对话链熔断——坑位⑭ 异构隔离同纪律）。
 */
@Data
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "rag.graph.extraction")
public class GraphExtractionProperties {

    /** 抽取模型端点（OpenAI 兼容，缺省百炼） */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 抽取模型密钥（YAML 缺省占位消费 DASHSCOPE_API_KEY） */
    private String apiKey = "";

    /** 抽取模型（低价档结构化输出） */
    private String model = "qwen3.7-plus";

    /** 结构化抽取求稳：零温度 */
    private double temperature = 0.0;

    /**
     * 单文档单次抽取响应上限（实体+关系 JSON）——v2.78 实证上调：2000 会截断
     * 富集片段的尾段关系（结构化 JSON 半途而断 → 整 chunk 结果弃失）。
     * 上限只约束不增费，留足头部空间。
     */
    private int maxTokens = 4000;

    /**
     * 每租户令牌桶速率（次/窗口）——避业务高峰 + 供应商 RPM 护栏。
     * v2.78 实证上调 10→20：限流只布节奏不增成本（调用总数不变），
     * 10 次/分使大文档（40+ chunk）令牌排队独耗 3 分半起，回填体验不可接受。
     */
    private int rate = 20;

    private int rateIntervalSeconds = 60;

    /** JVM 在飞抽取并发上限（信号量闸门，防虚拟线程无界打爆供应商） */
    private int concurrency = 3;

    /**
     * 实体描述嵌入单批条数——同 ETL 向量化分批纪律（{@code kb.etl.embed-batch-size}）：
     * DashScope embedding API 单请求硬限 ≤20 条，批量化免逐实体 HTTP 往返
     * （v2.78 实证：逐条形态 27 实体独耗 ~10s，百级实体文档级分钟计）。
     */
    private int embedBatchSize = 10;

    /** 单 chunk 进入抽取的截断阈值（字符） */
    private int maxChunkChars = 1500;

    /** 短于该长度的存活文本 chunk 不值得一次抽取调用 */
    private int minChunkChars = 20;
}
