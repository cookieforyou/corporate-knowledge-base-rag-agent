package com.enterprise.kb.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Graph 路检索调优参数（Phase5簇④ 5.2，{@code rag.graph.retrieval.*}）。
 *
 * <p>缺省值 = 保守形态（与双路基线共存不扰动）：种子实体 5 个 + 相似度阈值 0.7
 * + 1 跳邻域展开 + 过取 8 倍（v2.85 起）。检索期零 LLM 调用——全管线延迟预算
 * ~100ms 量级（10.8 扩表）。调参须配三路融合 kb-eval 基线对比（同双路调参纪律）。
 */
@Data
@ConfigurationProperties(prefix = "rag.graph.retrieval")
public class GraphRetrievalProperties {

    /** 向量索引种子实体匹配上限（**租户过滤后**的封顶值） */
    private int entityTopN = 5;

    /**
     * 向量索引过取倍数（v2.85）——索引侧取 {@code entityTopN × 本倍数} 条候选，
     * 租户过滤后再封顶回 {@code entityTopN} 个种子。
     *
     * <p><b>为何需要</b>：Neo4j 5.26 的 {@code db.index.vector.queryNodes} 无索引内
     * 过滤，租户条件只能后过滤——他租户近邻会占满 topN 名额使本租户零种子（实证：
     * 他租户 6 条 1.0 分候选使本租户 0.995 分候选完全不可见，而缺省仅 5 个名额）。
     * 过取倍数即名额补偿；代价仅向量比较次数（索引查询本身极廉）。
     * 置 1 = 关闭过取（回退旧行为）。饿死与否经
     * {@code rag.retrieval.graph.seed_starved} 指标观测（Prometheus 侧可据此调参）。
     */
    private int entityOverFetch = 8;

    /** 种子实体相似度下限（余弦） */
    private double entitySimilarityThreshold = 0.7;

    /** 1 跳邻域展开开关（邻居贡献 = 种子分 × 0.5，衰减固定于网关实现） */
    private boolean expandNeighbors = true;
}
