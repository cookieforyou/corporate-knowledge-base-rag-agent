package com.enterprise.kb.ai.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 语义缓存配置族（前缀 rag.cache，Phase 5 簇③ 5.6）。
 *
 * <p>查询级语义缓存：相似问句命中后短路「检索 + 重排 + 生成」全链，直接重放缓存
 * 回答与溯源。载体 = Redis 8 内建查询引擎（FT.* VECTOR HNSW/COSINE，Redis 8 GA
 * 起合入开源核心，无需 redis-stack），经项目既有 Redisson 客户端 {@code RSearch}
 * 类型化 API 消费，零新增依赖（复审定案：否决 Spring AI redis 向量存储模块——
 * 其底层 Jedis 与项目 Redisson 形成双客户端）。
 *
 * <p><b>缺省关纪律</b>：与 eval L2 / 间接注入评估同族——新能力默认关，用户侧
 * E2E 验证后按环境启用收真实流量（验收线：命中率 >30% + 命中延迟 P95 降 >40%，
 * 对照 18 §18.4 LT1 基线，08 章簇③验收）。
 *
 * <p><b>误命中风险控制（调研实证基线）</b>：企业问答场景生产阈值甜点区间
 * 余弦 0.90-0.95——缺省 0.95 保守起步，命中率不足时经配置下调观察，不做
 * 无评估证据的调参（同 rag.retrieval.* 纪律）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rag.cache")
public class SemanticCacheProperties {

    /** 总开关（缺省关——用户侧 E2E 验证后启用，同族缺省关纪律） */
    private boolean enabled = false;

    /**
     * 命中判定余弦相似度阈值：KNN 返回余弦距离（1 - 相似度，越小越近），
     * 距离 ≤ 1 - threshold 判命中。0.95 保守起步（行业生产区间 0.90-0.95 上沿），
     * 命中率不足时经配置下调并观察误命中。
     */
    private double similarityThreshold = 0.95;

    /** 条目 TTL（兜底防陈旧）：知识库内容变更另有事件驱动即时失效（批2 接线） */
    private Duration ttl = Duration.ofHours(1);

    /**
     * 嵌入向量维度——须与主检索链路 EmbeddingModel 一致（同 07 数据架构钉死的
     * 1024，与 pgvector/Milvus dimensions 同源）；维度漂移 → 索引重建
     */
    private int embeddingDim = 1024;
}
