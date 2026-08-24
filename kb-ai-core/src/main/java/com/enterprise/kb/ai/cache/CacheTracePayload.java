package com.enterprise.kb.ai.cache;

import java.util.List;
import java.util.Map;

/**
 * 缓存溯源载荷投影（Phase 5 簇③ 5.6 批2）：{@code RetrievalContext.TraceEntry} ↔ JSON 中间形态。
 *
 * <p>只持 SSE TRACE / 审计消费侧实际消费的字段（文本 / 得分 / 元数据），不序列化
 * 完整 {@code Document}（media / contentFormatter 等非 JSON 友好字段不入缓存）。
 * 命中重放时经 {@code Document.builder()} 重建，{@code AgentController.buildTraceEvent}
 * 与 {@code AuditTraceAdvisor.retrievalProjection} 消费的字段面完整保留。
 */
public record CacheTracePayload(String source, List<CachedChunk> chunks, Long latencyMs) {

    /** 单 chunk 投影：text（TRACE snippet 源）+ score（向量路原始相似度）+ metadata 全量（得分键/文档标识/页码） */
    public record CachedChunk(String text, Double score, Map<String, Object> metadata) {
    }
}
