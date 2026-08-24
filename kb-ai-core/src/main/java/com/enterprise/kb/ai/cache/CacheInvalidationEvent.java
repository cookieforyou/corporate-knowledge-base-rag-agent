package com.enterprise.kb.ai.cache;

/**
 * 缓存失效事件契约（Phase 5 簇③ 5.6 批2）：发布/订阅两侧共享，经
 * {@link CacheInvalidationListener#INVALIDATION_CHANNEL} 频道以 JSON String 传递
 * （StringCodec，同护栏重载频道 {@code rag:guardrail:reload} 纪律）。
 *
 * <p>知识库内容变更（ETL 重入库完成 / Chunk 编辑 / 软删 / 恢复）由写路径发布，
 * 缓存侧订阅后经 {@link SemanticCacheService#invalidateByDocument} 反查删除。
 */
public record CacheInvalidationEvent(String tenantId, String documentId) {
}
