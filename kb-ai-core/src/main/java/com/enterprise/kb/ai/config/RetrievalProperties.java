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
}
