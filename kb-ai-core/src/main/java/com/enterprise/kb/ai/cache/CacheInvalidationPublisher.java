package com.enterprise.kb.ai.cache;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 缓存失效事件发布器（Phase 5 簇③ 5.6 批2）：知识库内容变更写路径调用，
 * 经 Redis pub/sub 广播 {@link CacheInvalidationEvent}——多实例形态下全实例
 * 缓存同步失效（单实例亦经 Redis 回环送达，形态与多实例一致）。
 *
 * <p><b>接线四处（内容变更提交点）</b>：
 * <ul>
 *   <li>kb-api DocumentService 重入库进度回调 COMPLETED 终态帧——覆盖
 *       reparse / replace / 索引重建（经 ReindexGateway 委派同路径）/ 首次入库
 *       （新文档无存量缓存命中，失效为无害空转）；</li>
 *   <li>kb-admin ChunkOpsService 编辑 / 软删 / 恢复三门面。</li>
 * </ul>
 *
 * <p><b>fail-open 纪律</b>：发布失败仅 warn（TTL 兜底仍生效）——失效是优化件
 * 的及时性保障，不得击穿文档运维主流程。
 *
 * <p><b>缺省关</b>：与 {@link SemanticCacheService} 同条件装配——缓存关闭时
 * Bean 缺位，写路径经 {@code ObjectProvider} 容忍（零发布开销）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.cache", name = "enabled", havingValue = "true")
public class CacheInvalidationPublisher {

    private final RedissonClient redisson;
    private final JsonMapper jsonMapper;

    public CacheInvalidationPublisher(RedissonClient redisson, JsonMapper jsonMapper) {
        this.redisson = redisson;
        this.jsonMapper = jsonMapper;
    }

    /** 发布按文档失效事件；参数缺失或发布故障均静默降级（不阻断调用方主流程） */
    public void publish(String tenantId, String documentId) {
        if (tenantId == null || tenantId.isBlank() || documentId == null || documentId.isBlank()) {
            return;
        }
        try {
            String message = jsonMapper.writeValueAsString(new CacheInvalidationEvent(tenantId, documentId));
            redisson.getTopic(CacheInvalidationListener.INVALIDATION_CHANNEL, StringCodec.INSTANCE)
                .publish(message);
        } catch (Exception e) {
            log.warn("缓存失效事件发布失败（TTL 兜底仍生效）: tenant={}, docId={}, {}",
                tenantId, documentId, e.getMessage());
        }
    }
}
