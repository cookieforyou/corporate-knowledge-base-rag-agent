package com.enterprise.kb.ai.config;

import com.enterprise.kb.infrastructure.graph.GraphRecords;
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

    /**
     * 邻域展开方向（v3.02，缺省 {@code BOTH} = 保持既有召回行为）。
     *
     * <p>图模型是<b>有向</b>的（抽取提示词产出 {@code WORKS_AT / PART_OF / DEPENDS_ON /
     * PRODUCED_BY} 等方向性类型），而 1 跳展开是<b>召回机制</b>而非逻辑推理——缺省双向
     * 以保召回，方向敏感场景（如"上级/下级"类语义）可切 {@code OUTGOING} / {@code INCOMING}，
     * 以多跳集 A/B 实测定调。展开关闭（{@code expand-neighbors=false}）时本键被忽略。
     */
    private GraphRecords.ExpandDirection expandDirection = GraphRecords.ExpandDirection.BOTH;

    /**
     * 展开后候选实体总量上限（v3.02，缺省 100）：按 {@code hop ASC, 贡献分 DESC, id ASC}
     * 排序截断——<b>种子恒在</b>（hop=0 恒排前），只截断低贡献邻居。原形态无早剪枝
     * （计划实证：MENTIONS 展开与聚合整体落在最终 LIMIT 之前），高连接度 hub 实体
     * 会使中间结果按「种子 × 全邻居」膨胀；本上限使 MENTIONS 反查前的候选集有界
     * （作用域：候选之前的邻域收集仍与度数成正比——超大度数需另按度采样，属后续项）。
     * 不得低于 {@code entity-top-n}（网关侧按种子数兜底）。
     */
    private int candidateLimit = 100;
}
