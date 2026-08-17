package com.enterprise.kb.ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索链路调优参数（前缀 rag.retrieval）—— 簇① A3 配置化（复盘报告第五章 A3）
 *
 * <p>此前 topK/RRF_K/召回倍数/相似度阈值/单路超时散落于 {@code Constants} 与各组件
 * 私有常量，调参须改源码重发版，且 kb-eval 的 {@code eval.top-k} 与链路 topK 为两套
 * 独立配置存在漂移风险。收编为本配置组后：yml 默认值 = Phase 2 基线形态（行为零变化），
 * 调参走配置 + kb-eval 基线对比验证（A1/A4 的 A/B 流程基建）。
 *
 * <p>注意：修改任一参数都改变检索形态，须复跑 kb-eval 全量基线对比后再定默认值，
 * 不做无评估证据的调参。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {

    /** 最终证据条数（重排截断 / rerank top_n），也是召回基数（recallSize = topK × recallMultiplier） */
    private int topK = 5;

    /** RRF 融合常数（标准值 60，设计文档 10.4） */
    private int rrfK = 60;

    /** 召回放大系数：recallSize = topK × multiplier，给融合与重排留余量 */
    private int recallMultiplier = 2;

    /** 向量路相似度阈值（与 Phase 1 QuestionAnswerAdvisor 基线一致） */
    private double similarityThreshold = 0.5;

    /** 单路检索超时（秒）：超时即降级为空，不阻塞另一路（设计文档 10.8） */
    private int pathTimeoutSeconds = 5;

    /** 多查询扩展（pre-retrieval） */
    private Expansion expansion = new Expansion();

    /** 入库打标消费（安全簇④ D2，§9 定案④：默认关、度量后定案） */
    private InjectionHit injectionHit = new InjectionHit();

    /**
     * 多查询扩展配置组。
     *
     * <p>A1 A/B 决策（2026-08-11，chain 探针 102 条实测）：开启后 MRR 0.876→0.901、
     * Recall@5 0.897→0.903，枚举型 cross 用例部分复苏（cross-04 0→0.33）；但每查询
     * 增加 1 次扩展 LLM 调用 + N 倍检索调用，单查询 TTFT 必然突破 1.5s 目标——
     * 增益不抵延迟代价，**默认关**。重估触发：显式「深度检索」入口落地，或 A4
     * heading 富化后枚举型用例仍零召回（详见复盘报告第五章 A1 / 第六章 P2）。
     */
    @Getter
    @Setter
    public static class Expansion {
        /** 总开关（默认关，见类注 A/B 决策） */
        private boolean enabled = false;
        /** 扩展出的查询变体数（含检索调用放大倍数） */
        private int numQueries = 3;
    }

    /**
     * 入库打标 injection_hit 消费（安全簇④ D2，缺口 E3 打标消费面）。
     *
     * <p>S4 ETL 入库扫描对命中注入词表的 chunk 打标（kb_chunk.metadata JSONB），
     * 经 vectorMetadata / EsChunkDoc 契约携带至检索侧（簇④ D2 补齐）。降权
     * **默认关闭**（§9 定案④）：先经 kb-eval 检索门禁（Recall/MRR）开关双跑
     * 度量影响，再定开关口径——本配置只落机制，不改变默认生产行为。
     */
    @Getter
    @Setter
    public static class InjectionHit {

        private Demote demote = new Demote();

        @Getter
        @Setter
        public static class Demote {
            /** 降权开关（默认关，度量后定案） */
            private boolean enabled = false;
            /** 融合分衰减系数：fused_score × factor（0-1，越小压制越强） */
            private double factor = 0.5;
        }
    }
}
