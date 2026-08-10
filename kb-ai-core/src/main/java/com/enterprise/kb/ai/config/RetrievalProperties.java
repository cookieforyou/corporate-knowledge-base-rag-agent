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
}
