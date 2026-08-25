package com.enterprise.kb.ai.cache;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RKeys;
import org.redisson.api.RMap;
import org.redisson.api.RSearch;
import org.redisson.api.RedissonClient;
import org.redisson.api.search.query.Document;
import org.redisson.api.search.query.QueryOptions;
import org.redisson.api.search.query.SearchResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 语义缓存核心服务测试（Phase 5 簇③ 5.6 批1）——KNN 命中判定（余弦距离 → 相似度
 * 阈值）/ 确定性键与幂等写入 / 按文档失效 / fail-open 容错纪律 / FLOAT32 小端契约。
 */
class SemanticCacheServiceTest {

    private RedissonClient redisson;
    private RSearch search;
    private RKeys keys;
    private SimpleMeterRegistry meterRegistry;
    private SemanticCacheProperties properties;
    private SemanticCacheService service;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        search = mock(RSearch.class);
        keys = mock(RKeys.class);
        meterRegistry = new SimpleMeterRegistry();
        properties = new SemanticCacheProperties();
        when(redisson.getSearch(any(org.redisson.client.codec.Codec.class))).thenReturn(search);
        when(redisson.getKeys()).thenReturn(keys);
        // 探测通过（getIndexes 不抛异常）
        when(search.getIndexes()).thenReturn(List.of());
        service = new SemanticCacheService(redisson, properties, new AiBusinessMetrics(meterRegistry));
    }

    private static float[] vector(float... values) {
        return values;
    }

    // ── 启动期能力探测 ──

    @Test
    void probeFailureMarksUnavailableAndAllOperationsPassThrough() {
        when(search.getIndexes()).thenThrow(new RuntimeException("unknown command 'FT._LIST'"));
        SemanticCacheService degraded = new SemanticCacheService(redisson, properties,
                new AiBusinessMetrics(new SimpleMeterRegistry()));

        assertThat(degraded.isAvailable()).isFalse();
        assertThat(degraded.lookup("t-1", vector(1f, 2f))).isEmpty();
        degraded.put("t-1", entry("问题"), vector(1f, 2f));
        assertThat(degraded.invalidateByDocument("t-1", "doc-1")).isZero();
        // 自关后零 Redis 触达（探测调用除外）
        verify(search, never()).search(anyString(), anyString(), any());
        verify(redisson, never()).getMap(anyString(), any(org.redisson.client.codec.Codec.class));
    }

    // ── KNN 命中判定 ──

    @Test
    void lookupHitWhenSimilarityAboveThreshold() {
        Document doc = new Document("kb:cache:t-1:abc",
                Map.of("question", "什么是增值税发票？", "answer", "回答正文 [ref-1]",
                        "trace", "[{\"source\":\"final\"}]", "docIds", "doc-1,doc-2",
                        "dist", "0.03"));
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(1, List.of(doc)));
        when(search.hasIndex(anyString())).thenReturn(true);

        Optional<SemanticCacheService.CacheHit> hit = service.lookup("t-1", vector(0.1f, 0.2f));

        assertThat(hit).isPresent();
        assertThat(hit.get().answer()).isEqualTo("回答正文 [ref-1]");
        assertThat(hit.get().traceJson()).isEqualTo("[{\"source\":\"final\"}]");
        assertThat(hit.get().docIds()).containsExactly("doc-1", "doc-2");
        assertThat(hit.get().similarity()).isEqualTo(0.97);
        assertThat(meterRegistry.counter("rag.retrieval.cache.hit").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.retrieval.cache.miss").count()).isZero();
    }

    @Test
    void lookupHitAtExactThresholdBoundary() {
        Document doc = new Document("kb:cache:t-1:abc", Map.of("dist", "0.05", "answer", "a"));
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(1, List.of(doc)));
        when(search.hasIndex(anyString())).thenReturn(true);

        // dist 0.05 → similarity 0.95 == 阈值 0.95 → 命中（< 阈值才判 miss）
        assertThat(service.lookup("t-1", vector(0.1f))).isPresent();
    }

    @Test
    void lookupMissWhenSimilarityBelowThreshold() {
        Document doc = new Document("kb:cache:t-1:abc", Map.of("dist", "0.10", "answer", "a"));
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(1, List.of(doc)));
        when(search.hasIndex(anyString())).thenReturn(true);

        assertThat(service.lookup("t-1", vector(0.1f))).isEmpty();
        assertThat(meterRegistry.counter("rag.retrieval.cache.miss").count()).isEqualTo(1.0);
    }

    @Test
    void lookupMissWhenIndexEmpty() {
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));
        when(search.hasIndex(anyString())).thenReturn(true);

        assertThat(service.lookup("t-1", vector(0.1f))).isEmpty();
        assertThat(meterRegistry.counter("rag.retrieval.cache.miss").count()).isEqualTo(1.0);
    }

    @Test
    void lookupSendsKnnQueryWithFloat32BlobAndDialect2() {
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));
        when(search.hasIndex(anyString())).thenReturn(true);

        service.lookup("t-1", vector(1.0f));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<QueryOptions> options = ArgumentCaptor.forClass(QueryOptions.class);
        verify(search).search(eq("kb-cache-idx-t-1"), query.capture(), options.capture());
        assertThat(query.getValue()).isEqualTo("*=>[KNN 1 @embedding $BLOB AS dist]");
        assertThat(options.getValue().getDialect()).isEqualTo(2);
        byte[] blob = (byte[]) options.getValue().getParams().get("BLOB");
        // FLOAT32 小端：1.0f = 0x3F800000 → 小端字节序 00 00 80 3F
        assertThat(blob).containsExactly(0x00, 0x00, 0x80, 0x3F);
    }

    @Test
    void lookupRuntimeFailureFailsOpen() {
        when(search.search(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"));
        when(search.hasIndex(anyString())).thenReturn(true);

        assertThat(service.lookup("t-1", vector(0.1f))).isEmpty();
        assertThat(meterRegistry.counter("rag.retrieval.cache.miss").count()).isEqualTo(1.0);
    }

    // ── 写入 ──

    @Test
    @SuppressWarnings("unchecked")
    void putWritesHashFieldsWithTtlAndDeterministicKey() {
        RMap<String, Object> map = mock(RMap.class);
        org.mockito.Mockito.doReturn(map).when(redisson)
                .getMap(anyString(), any(org.redisson.client.codec.Codec.class));
        when(search.hasIndex(anyString())).thenReturn(true);

        service.put("t-1", entry("什么是增值税发票？"), vector(1.0f));

        // 确定性键：根前缀 + 问句指纹（同问句幂等覆盖）
        String expectedKey = SemanticCacheService.entryKey("t-1", "什么是增值税发票？");
        verify(redisson).getMap(eq(expectedKey), any(org.redisson.client.codec.Codec.class));
        verify(map).put("question", "什么是增值税发票？");
        verify(map).put("answer", "回答 [ref-1]");
        verify(map).put("docIds", "doc-9");
        verify(map).put(eq("embedding"), any(byte[].class));
        verify(map).expire(Duration.ofHours(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void putSameQuestionTwiceHitsSameKey() {
        RMap<String, Object> map = mock(RMap.class);
        org.mockito.Mockito.doReturn(map).when(redisson)
                .getMap(anyString(), any(org.redisson.client.codec.Codec.class));
        when(search.hasIndex(anyString())).thenReturn(true);

        service.put("t-1", entry("同一问题"), vector(1.0f));
        service.put("t-1", entry("同一问题"), vector(2.0f));

        String expectedKey = SemanticCacheService.entryKey("t-1", "同一问题");
        verify(redisson, org.mockito.Mockito.times(2))
                .getMap(eq(expectedKey), any(org.redisson.client.codec.Codec.class));
    }

    @Test
    void putRuntimeFailureSwallowed() {
        org.mockito.Mockito.doThrow(new RuntimeException("down")).when(redisson)
                .getMap(anyString(), any(org.redisson.client.codec.Codec.class));
        when(search.hasIndex(anyString())).thenReturn(true);

        // 不抛出——写入失败不传导
        service.put("t-1", entry("问题"), vector(1.0f));
    }

    // ── 按文档失效 ──

    @Test
    void invalidateByDocumentDeletesMatchedEntries() {
        when(search.search(anyString(), anyString(), any())).thenReturn(new SearchResult(2,
                List.of(new Document("kb:cache:t-1:k1", Map.of()),
                        new Document("kb:cache:t-1:k2", Map.of()))));
        when(search.hasIndex(anyString())).thenReturn(true);

        int deleted = service.invalidateByDocument("t-1", "doc-9");

        assertThat(deleted).isEqualTo(2);
        verify(keys).delete("kb:cache:t-1:k1", "kb:cache:t-1:k2");
        assertThat(meterRegistry.counter("rag.retrieval.cache.invalidated").count()).isEqualTo(2.0);
    }

    @Test
    void invalidateUsesTagQueryOnDocIdsField() {
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));
        when(search.hasIndex(anyString())).thenReturn(true);

        service.invalidateByDocument("t-1", "doc-9");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(search).search(eq("kb-cache-idx-t-1"), query.capture(), any());
        // 坑位㉟：TAG 表达式内 - 为否定符，连字符须转义（存储侧原值不动）
        assertThat(query.getValue()).isEqualTo("@docIds:{doc\\-9}");
    }

    @Test
    void invalidateEscapesUuidDocumentIdInTagQuery() {
        // 实证形态：文档引用为 UUID（含连字符），未转义即 Syntax error（坑位㉟）
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));
        when(search.hasIndex(anyString())).thenReturn(true);

        service.invalidateByDocument("t-1", "c966ddac-e9af-4f3e-a0cd-171159e0a26f");

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(search).search(eq("kb-cache-idx-t-1"), query.capture(), any());
        assertThat(query.getValue()).isEqualTo(
                "@docIds:{c966ddac\\-e9af\\-4f3e\\-a0cd\\-171159e0a26f}");
    }

    @Test
    void escapeTagValueOnlyEscapesTagSpecialCharacters() {
        assertThat(SemanticCacheService.escapeTagValue("c966ddac-e9af"))
                .isEqualTo("c966ddac\\-e9af");
        // 字母数字与下划线原样通过（sanitize 后值域只含 [a-zA-Z0-9_-]）
        assertThat(SemanticCacheService.escapeTagValue("doc_9x")).isEqualTo("doc_9x");
        assertThat(SemanticCacheService.escapeTagValue("")).isEmpty();
    }

    @Test
    void invalidateZeroWhenNoMatch() {
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));
        when(search.hasIndex(anyString())).thenReturn(true);

        assertThat(service.invalidateByDocument("t-1", "doc-x")).isZero();
        verifyNoInteractions(keys);
    }

    // ── 索引惰性创建 ──

    @Test
    void indexCreatedOnceWithTenantIsolatedPrefix() {
        when(search.hasIndex("kb-cache-idx-t-1")).thenReturn(false);
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));

        service.lookup("t-1", vector(0.1f));
        service.lookup("t-1", vector(0.2f));

        // 双次触达仅建一次（hasIndex 二次确认短路）
        verify(search).createIndex(eq("kb-cache-idx-t-1"), any(), any(), any());
    }

    @Test
    void unknownIndexNameWordingTreatedAsAbsentAndIndexCreated() {
        // 坑位㉝：Redis Stack / RediSearch 对不存在索引返回 "Unknown index name"，
        // Redisson 4.6.1 hasIndex 错误匹配表（not found / no such index）不覆盖该
        // 文案 → 上抛异常；须按「不存在」兜住并创建（否则惰性建索引永不执行）
        when(search.hasIndex("kb-cache-idx-t-1")).thenThrow(new RuntimeException(
                "Unknown index name. command: (EVALSHA_RO, cached script: FT.INFO ...)"));
        when(search.search(anyString(), anyString(), any()))
                .thenReturn(new SearchResult(0, List.of()));

        service.lookup("t-1", vector(0.1f));

        verify(search).createIndex(eq("kb-cache-idx-t-1"), any(), any(), any());
    }

    @Test
    void nonWordingHasIndexFailurePropagatesToFailOpenWithoutCreation() {
        // 连接故障等非文案异常不得误判为「不存在」——上抛由外层 fail-open 兜底
        when(search.hasIndex(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(service.lookup("t-1", vector(0.1f))).isEmpty();
        verify(search, never()).createIndex(anyString(), any(), any(), any());
    }

    // ── 纯函数契约 ──

    @Test
    void float32BytesAreLittleEndian() {
        byte[] bytes = SemanticCacheService.toFloat32Bytes(new float[]{1.0f, -1.0f});
        assertThat(bytes).hasSize(8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getFloat()).isEqualTo(1.0f);
        assertThat(buffer.getFloat()).isEqualTo(-1.0f);
    }

    @Test
    void fingerprintIsDeterministicAndCollisionResistantForDistinctQuestions() {
        String first = SemanticCacheService.fingerprint("什么是增值税发票？");
        String second = SemanticCacheService.fingerprint("什么是增值税发票？");
        String other = SemanticCacheService.fingerprint("如何申请发票增额？");
        assertThat(first).isEqualTo(second).hasSize(16);
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    void sanitizeReplacesUnsafeCharacters() {
        assertThat(SemanticCacheService.sanitize("tenant_001")).isEqualTo("tenant_001");
        assertThat(SemanticCacheService.sanitize("a:b/c.d")).isEqualTo("a_b_c_d");
    }

    @Test
    void entryKeyCombinesTenantPrefixAndFingerprint() {
        String key = SemanticCacheService.entryKey("t-1", "问题");
        assertThat(key).startsWith("kb:cache:t-1:")
                .isEqualTo("kb:cache:t-1:" + SemanticCacheService.fingerprint("问题"));
    }

    private static SemanticCacheEntry entry(String question) {
        return new SemanticCacheEntry(question, "回答 [ref-1]", "[{\"source\":\"final\"}]",
                List.of("doc-9"), Instant.parse("2026-08-24T10:00:00Z"));
    }
}
