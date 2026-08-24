package com.enterprise.kb.ai.cache;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RSearch;
import org.redisson.api.RedissonClient;
import org.redisson.api.search.index.FieldIndex;
import org.redisson.api.search.index.IndexOptions;
import org.redisson.api.search.index.IndexType;
import org.redisson.api.search.index.VectorDistParam;
import org.redisson.api.search.index.VectorTypeParam;
import org.redisson.api.search.query.Document;
import org.redisson.api.search.query.QueryOptions;
import org.redisson.api.search.query.ReturnAttribute;
import org.redisson.api.search.query.SearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义缓存核心服务（Phase 5 簇③ 5.6）：查询级语义缓存的存取与失效。
 *
 * <p><b>载体</b>：Redis 8 内建查询引擎（FT.* VECTOR，Redis 8 GA 起合入开源核心）
 * 经项目既有 Redisson 客户端 {@code RSearch} 类型化 API 消费，零新增依赖
 * （复审定案否决 Spring AI redis 向量存储模块——其底层 Jedis 与项目 Redisson
 * 形成双客户端）。
 *
 * <p><b>形态</b>：每租户一索引（{@code kb-cache-idx-{tenant}}）+ 前缀域键
 * （{@code kb:cache:{tenant}:{entryId}}）——租户隔离与检索侧「有 ctx 无租户
 * 返回空」同语义，索引域物理分离，跨租户命中结构性不可达（同双向量库
 * 条件装配的域隔离纪律）。条目 = HASH 文档：嵌入向量（HNSW/COSINE/FLOAT32
 * 小端）+ 问句/回答/溯源载荷/文档引用（TAG）/写入时刻。
 *
 * <p><b>命中语义</b>：KNN top-1 + 余弦相似度 ≥ {@code rag.cache.similarity-threshold}
 * （KNN 返回余弦距离 = 1 - 相似度，距离 ≤ 1 - 阈值判命中）。
 *
 * <p><b>容错纪律（同限流/配额族）</b>：Redis 故障 / 搜索引擎不可用 → fail-open
 * 直通（缓存是优化件不是防线，故障不得传导为对话链失败）；启动期探测不可用
 * 即整体自关 + warn 日志。
 *
 * <p><b>缺省关</b>：{@code @ConditionalOnProperty} 整体条件装配——
 * {@code rag.cache.enabled=false}（缺省）时 Bean 不注册，消费方经
 * {@code ObjectProvider} 容忍缺位（批2 CacheCheckAdvisor）。kb-eval 零影响。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "rag.cache", name = "enabled", havingValue = "true")
public class SemanticCacheService {

    /** 条目键根前缀（FT.CREATE PREFIX 域 + 键构造同源） */
    static final String KEY_PREFIX_ROOT = "kb:cache:";
    /** 索引名前缀（索引名不收冒号，分隔符独立） */
    static final String INDEX_PREFIX = "kb-cache-idx-";
    /** 失效反查单轮批量上限（单租户索引规模远小于此，超出分批续扫场景登记扩展点） */
    static final int INVALIDATION_BATCH = 1000;

    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_QUESTION = "question";
    private static final String FIELD_ANSWER = "answer";
    private static final String FIELD_TRACE = "trace";
    private static final String FIELD_DOC_IDS = "docIds";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String KNN_QUERY = "*=>[KNN 1 @" + FIELD_EMBEDDING + " $BLOB AS dist]";

    private final RedissonClient redisson;
    private final SemanticCacheProperties properties;
    private final AiBusinessMetrics metrics;
    private final RSearch search;
    private final Set<String> ensuredIndexes = ConcurrentHashMap.newKeySet();
    private volatile boolean available;

    public SemanticCacheService(RedissonClient redisson, SemanticCacheProperties properties,
                                AiBusinessMetrics metrics) {
        this.redisson = redisson;
        this.properties = properties;
        this.metrics = metrics;
        this.search = redisson.getSearch(CacheDocCodec.INSTANCE);
        probeAvailability();
    }

    /** 启动期能力探测：搜索引擎不可用（非 Redis 8 内建引擎构建）→ 整体自关 fail-open */
    private void probeAvailability() {
        try {
            search.getIndexes();
            this.available = true;
            log.info("语义缓存能力探测通过（Redis 内建搜索引擎可用），阈值={}, TTL={}",
                    properties.getSimilarityThreshold(), properties.getTtl());
        } catch (Exception e) {
            this.available = false;
            log.warn("Redis 搜索引擎不可用，语义缓存整体自关（fail-open 直通）：{}", e.getMessage());
        }
    }

    /** 能力态（探测失败或 Redis 运行期故障降级后为 false，消费方直通） */
    public boolean isAvailable() {
        return available;
    }

    /**
     * KNN top-1 查找：相似度达阈返回命中（含可重放回答与溯源载荷），否则空。
     * 任何运行期异常 → fail-open 返回空并计 miss（对话链不受缓存故障影响）。
     */
    public Optional<CacheHit> lookup(String tenantId, float[] queryVector) {
        if (!available || queryVector == null || queryVector.length == 0) {
            return Optional.empty();
        }
        try {
            ensureIndex(tenantId);
            SearchResult result = search.search(indexName(tenantId), KNN_QUERY,
                    QueryOptions.defaults()
                            .params(Map.of("BLOB", toFloat32Bytes(queryVector)))
                            .dialect(2)
                            .returnAttributes(new ReturnAttribute(FIELD_QUESTION),
                                    new ReturnAttribute(FIELD_ANSWER),
                                    new ReturnAttribute(FIELD_TRACE),
                                    new ReturnAttribute(FIELD_DOC_IDS),
                                    new ReturnAttribute("dist")));
            if (result.getTotal() == 0 || result.getDocuments().isEmpty()) {
                metrics.recordCacheLookup(false);
                return Optional.empty();
            }
            Document doc = result.getDocuments().get(0);
            Map<String, Object> attrs = doc.getAttributes();
            double distance = Double.parseDouble(String.valueOf(attrs.get("dist")));
            double similarity = 1.0 - distance;
            if (similarity < properties.getSimilarityThreshold()) {
                metrics.recordCacheLookup(false);
                return Optional.empty();
            }
            metrics.recordCacheLookup(true);
            List<String> docIds = splitDocIds(String.valueOf(attrs.getOrDefault(FIELD_DOC_IDS, "")));
            return Optional.of(new CacheHit(
                    String.valueOf(attrs.getOrDefault(FIELD_QUESTION, "")),
                    String.valueOf(attrs.getOrDefault(FIELD_ANSWER, "")),
                    String.valueOf(attrs.getOrDefault(FIELD_TRACE, "")),
                    docIds, similarity));
        } catch (Exception e) {
            log.warn("语义缓存查找失败，fail-open 直通：{}", e.getMessage());
            metrics.recordCacheLookup(false);
            return Optional.empty();
        }
    }

    /**
     * 写入缓存条目：条目键由问句指纹确定性派生（同问句幂等覆盖），
     * TTL = {@code rag.cache.ttl}。入库门槛由写入侧（批2 advisor）把关。
     */
    public void put(String tenantId, SemanticCacheEntry entry, float[] vector) {
        if (!available || entry == null || vector == null || vector.length == 0) {
            return;
        }
        try {
            ensureIndex(tenantId);
            RMap<String, Object> doc = redisson.getMap(entryKey(tenantId, entry.question()),
                    CacheDocCodec.INSTANCE);
            doc.put(FIELD_QUESTION, entry.question());
            doc.put(FIELD_ANSWER, entry.answer());
            doc.put(FIELD_TRACE, entry.traceJson() == null ? "" : entry.traceJson());
            doc.put(FIELD_DOC_IDS, String.join(",", entry.docIds()));
            doc.put(FIELD_CREATED_AT, String.valueOf(entry.createdAt().toEpochMilli()));
            doc.put(FIELD_EMBEDDING, toFloat32Bytes(vector));
            doc.expire(properties.getTtl());
        } catch (Exception e) {
            log.warn("语义缓存写入失败，忽略（下次同问句重新生成后再入）：{}", e.getMessage());
        }
    }

    /**
     * 按文档失效：知识库内容变更（入库/编辑/重建/软删）时反查引用该文档的
     * 全部缓存条目并删除，返回删除条数。文档引用经条目 docIds TAG 字段索引。
     */
    public int invalidateByDocument(String tenantId, String documentId) {
        if (!available || documentId == null || documentId.isBlank()) {
            return 0;
        }
        try {
            ensureIndex(tenantId);
            SearchResult result = search.search(indexName(tenantId),
                    "@" + FIELD_DOC_IDS + ":{" + sanitize(documentId) + "}",
                    QueryOptions.defaults().noContent(true).limit(0, INVALIDATION_BATCH));
            if (result.getTotal() == 0) {
                return 0;
            }
            List<String> keys = new ArrayList<>();
            for (Document doc : result.getDocuments()) {
                keys.add(doc.getId());
            }
            redisson.getKeys().delete(keys.toArray(String[]::new));
            metrics.recordCacheInvalidated(keys.size());
            return keys.size();
        } catch (Exception e) {
            log.warn("语义缓存按文档失效失败（TTL 兜底仍生效）：{}", e.getMessage());
            return 0;
        }
    }

    /** 租户索引惰性创建（首触达建，hasIndex 短路重复建） */
    private void ensureIndex(String tenantId) {
        String index = indexName(tenantId);
        if (ensuredIndexes.contains(index)) {
            return;
        }
        synchronized (ensuredIndexes) {
            if (ensuredIndexes.contains(index)) {
                return;
            }
            if (!search.hasIndex(index)) {
                search.createIndex(index,
                        IndexOptions.defaults().on(IndexType.HASH).prefix(keyPrefix(tenantId)),
                        FieldIndex.hnswVector(FIELD_EMBEDDING)
                                .type(VectorTypeParam.Type.FLOAT32)
                                .dim(properties.getEmbeddingDim())
                                .distance(VectorDistParam.DistanceMetric.COSINE),
                        FieldIndex.tag(FIELD_DOC_IDS).separator(","));
                log.info("语义缓存租户索引已建：{}（dim={}, 前缀 {}）",
                        index, properties.getEmbeddingDim(), keyPrefix(tenantId));
            }
            ensuredIndexes.add(index);
        }
    }

    static String indexName(String tenantId) {
        return INDEX_PREFIX + sanitize(tenantId);
    }

    static String keyPrefix(String tenantId) {
        return KEY_PREFIX_ROOT + sanitize(tenantId) + ":";
    }

    /** 条目键：根前缀 + 问句指纹（SHA-256 前 8 字节十六进制，同问句幂等覆盖） */
    static String entryKey(String tenantId, String question) {
        return keyPrefix(tenantId) + fingerprint(question);
    }

    static String fingerprint(String question) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(question.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** FLOAT32 小端字节序——Redis VECTOR 字段契约（与 JVM 默认大端相反，显式钉死） */
    static byte[] toFloat32Bytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /** 索引名/键域净化：仅收字母数字下划线连字符（租户域隔离的命名卫生） */
    static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static List<String> splitDocIds(String joined) {
        if (joined == null || joined.isBlank() || "null".equals(joined)) {
            return List.of();
        }
        return List.of(joined.split(","));
    }

    /**
     * 缓存命中载荷：回答 + 溯源载荷原样重放（批2 advisor 消费），
     * 不重新检索——命中语义即「证据与生成已固化」。
     */
    public record CacheHit(String question, String answer, String traceJson,
                           List<String> docIds, double similarity) {
    }
}
