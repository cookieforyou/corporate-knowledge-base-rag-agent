package com.enterprise.kb.etl.pipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * ETL 进度 Redis 写入器（设计文档 9.6，任务 2.13）
 *
 * <p>双通道输出：
 * <ul>
 *   <li>状态通道：Hash 键 {@code etl:progress:{docId}}（第七章 7.5 键规划，TTL 24h）——
 *       前端重连/晚订阅时读取最新进度；</li>
 *   <li>实时通道：Pub/Sub 频道 {@code etl:progress} 全量广播进度 JSON——
 *       kb-api 的 WebSocket 端点订阅后按 docId 分发前端会话。</li>
 * </ul>
 *
 * <p>失败隔离：Redis 不可用仅告警，**不阻断 ETL**（PG 为事实源，进度为增值数据）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtlProgressRedisWriter implements Consumer<EtlProgress> {

    /** 进度 Hash 键前缀（7.5 键规划：etl:progress:{docId}，TTL 24h） */
    public static final String KEY_PREFIX = "etl:progress:";
    /** 实时推送频道（kb-api WebSocket 端点订阅） */
    public static final String CHANNEL = "etl:progress";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public void accept(EtlProgress progress) {
        try {
            String key = KEY_PREFIX + progress.getDocId();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("stage", String.valueOf(progress.getStage()));
            fields.put("documentCount", String.valueOf(progress.getDocumentCount()));
            fields.put("chunkCount", String.valueOf(progress.getChunkCount()));
            fields.put("processedChunks", String.valueOf(progress.getProcessedChunks()));
            fields.put("percentage", String.valueOf(progress.getPercentage()));
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, TTL);
            redisTemplate.convertAndSend(CHANNEL, jsonMapper.writeValueAsString(progress));
        } catch (Exception e) {
            log.warn("ETL 进度写入 Redis 失败（不阻断 ETL）: docId={}, {}",
                progress.getDocId(), e.getMessage());
        }
    }
}
