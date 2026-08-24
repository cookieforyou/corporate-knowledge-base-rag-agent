package com.enterprise.kb.ai.cache;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 缓存失效事件订阅器（Phase 5 簇③ 5.6 批2）：订阅
 * {@link #INVALIDATION_CHANNEL} 频道，经 {@link SemanticCacheService#invalidateByDocument}
 * 反查删除引用变更文档的缓存条目。
 *
 * <p><b>装配形态对齐 {@code GuardrailReloadCoordinator}（pub/sub 先例）</b>：
 * StringCodec 频道 + String 消息（JSON 载荷）+ {@code @PostConstruct} 挂监听 /
 * {@code @PreDestroy} 退订 + 订阅动作本身 try/catch fail-open（订阅失败仅 warn——
 * TTL 兜底仍保证缓存最终过期）。
 *
 * <p><b>缺省关</b>：与 {@link SemanticCacheService} 同条件装配——缓存关闭时
 * 不订阅（无消费方的频道零开销）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.cache", name = "enabled", havingValue = "true")
public class CacheInvalidationListener {

    /** 缓存失效广播频道（命名对齐护栏重载频道 {@code rag:guardrail:reload} 纪律） */
    public static final String INVALIDATION_CHANNEL = "rag:cache:invalidate";

    private final RedissonClient redisson;
    private final SemanticCacheService cacheService;
    private final JsonMapper jsonMapper;
    private RTopic topic;
    private int listenerId;

    public CacheInvalidationListener(RedissonClient redisson,
                                     SemanticCacheService cacheService,
                                     JsonMapper jsonMapper) {
        this.redisson = redisson;
        this.cacheService = cacheService;
        this.jsonMapper = jsonMapper;
    }

    @PostConstruct
    void start() {
        try {
            topic = redisson.getTopic(INVALIDATION_CHANNEL, StringCodec.INSTANCE);
            listenerId = topic.addListener(String.class, (channel, message) -> onMessage(message));
            log.info("语义缓存失效频道已订阅：{}", INVALIDATION_CHANNEL);
        } catch (Exception e) {
            log.warn("语义缓存失效频道订阅失败（TTL 兜底仍生效）：{}", e.getMessage());
        }
    }

    /** 消息处理：反序列化 → 按文档失效；畸形消息/失效故障仅 warn（不击穿订阅线程） */
    private void onMessage(String message) {
        try {
            CacheInvalidationEvent event = jsonMapper.readValue(message, CacheInvalidationEvent.class);
            int deleted = cacheService.invalidateByDocument(event.tenantId(), event.documentId());
            if (deleted > 0) {
                log.info("语义缓存按文档失效：tenant={}, docId={}, 删除 {} 条",
                    event.tenantId(), event.documentId(), deleted);
            }
        } catch (Exception e) {
            log.warn("缓存失效消息处理失败（TTL 兜底仍生效）：{}", e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        if (topic == null) {
            return;
        }
        try {
            topic.removeListener(listenerId);
        } catch (Exception e) {
            log.warn("语义缓存失效频道退订失败：{}", e.getMessage());
        }
    }
}
