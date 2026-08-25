package com.enterprise.kb.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph 路检索调优参数（簇④ 5.2，{@code rag.graph.retrieval.*}）。
 *
 * <p>缺省值 = 保守形态（与双路基线共存不扰动）：种子实体 5 个 + 相似度阈值 0.7
 * + 1 跳邻域展开。检索期零 LLM 调用——全管线延迟预算 ~100ms 量级（10.8 扩表）。
 * 调参须配三路融合 kb-eval 基线对比（同双路调参纪律）。
 */
@Data
@ConfigurationProperties(prefix = "rag.graph.retrieval")
public class GraphRetrievalProperties {

    /** 向量索引种子实体匹配上限 */
    private int entityTopN = 5;

    /** 种子实体相似度下限（余弦） */
    private double entitySimilarityThreshold = 0.7;

    /** 1 跳邻域展开开关（邻居贡献 = 种子分 × 0.5，衰减固定于网关实现） */
    private boolean expandNeighbors = true;
}
