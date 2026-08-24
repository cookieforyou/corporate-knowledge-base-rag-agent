package com.enterprise.kb.admin.service;

import com.enterprise.kb.ai.cache.CacheInvalidationPublisher;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.service.ChunkCleanupService;
import com.enterprise.kb.etl.service.DocumentEtlService;
import com.enterprise.kb.etl.transformer.HtmlProtectingSplitter;
import com.enterprise.kb.etl.transformer.SanitizingTransformer;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Chunk 运维服务（Phase 4 簇③ 4.4）——编辑（异步重嵌入）/ 软删 / 恢复三门面，
 * 复用 C1 基座：软删委派 {@link ChunkCleanupService#softDelete}，向量元数据契约
 * 与 ES 双写复用 {@link DocumentEtlService#vectorMetadata} / {@link EsIndexWriter}。
 *
 * <p><b>一致性模型</b>（同 9.4）：PG kb_chunk 为唯一事实源——编辑/恢复同步写 PG，
 * 向量库/ES 经 etlExecutor 异步重嵌入，失败仅告警不阻断（重建兜底，4.5）。
 *
 * <p><b>重嵌入两步形态</b>（实现期源码级核验定案）：pgvector add 为
 * {@code INSERT ... ON CONFLICT DO UPDATE} 真 upsert，而 MilvusVectorStore add
 * 为普通 insert（同 ID 重复写入无覆写保证）——故统一 delete → add 两步，
 * 双向量库后端同语义；异步窗口内短暂检索缺失为运维操作可接受形态。
 *
 * <p><b>守卫序列</b>（fail-closed）：chunk 不存在 / 文档不存在 / 跨租户一律
 * CHUNK_NOT_FOUND（不泄露存在性，同反馈域 MESSAGE_NOT_FOUND 语义）；文档处理中
 * （UPLOADING/PARSING/REINDEXING）→ DOC_NOT_READY（防与在途 ETL 竞态互覆）。
 *
 * <p><b>已知语义</b>：编辑为运维补丁——后续 reparse/replace 以 MinIO 原件重走
 * 管线，蓝绿 diff 会覆写/清除手工编辑（PG 事实源回归原件）；chunk ID 不变
 * （确定性 ID 锚点，Golden 标注不因编辑失效）。
 */
@Slf4j
@Service
public class ChunkOpsService {

    private final KbChunkRepository chunkRepository;
    private final KbDocumentRepository documentRepository;
    private final ChunkCleanupService chunkCleanupService;
    private final VectorStore vectorStore;
    private final EsIndexWriter esIndexWriter;
    private final SanitizingTransformer sanitizingTransformer;
    private final AiBusinessMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Executor etlExecutor;
    /** 语义缓存失效发布器（簇③ 5.6 批2）：缺省关时 Bean 缺位，ObjectProvider 容忍 */
    private final ObjectProvider<CacheInvalidationPublisher> cacheInvalidationPublisher;

    public ChunkOpsService(KbChunkRepository chunkRepository,
                           KbDocumentRepository documentRepository,
                           ChunkCleanupService chunkCleanupService,
                           VectorStore vectorStore,
                           EsIndexWriter esIndexWriter,
                           SanitizingTransformer sanitizingTransformer,
                           AiBusinessMetrics metrics,
                           JsonMapper jsonMapper,
                           @Qualifier("etlExecutor") Executor etlExecutor,
                           ObjectProvider<CacheInvalidationPublisher> cacheInvalidationPublisher) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.chunkCleanupService = chunkCleanupService;
        this.vectorStore = vectorStore;
        this.esIndexWriter = esIndexWriter;
        this.sanitizingTransformer = sanitizingTransformer;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.etlExecutor = etlExecutor;
        this.cacheInvalidationPublisher = cacheInvalidationPublisher;
    }

    /**
     * 编辑 chunk 文本：与 ETL 同源消毒（PII 掩码 + 注入打标）→ 同步写 PG →
     * 异步重嵌入（向量 delete→add + ES 覆写）。
     *
     * <p>original_content 不动——其语义为「保护块原始 HTML / 语境增强前原文」
     * （确定性 ID 散列源与溯源载体），非编辑备份（14.1 草图语义实证修正）。
     */
    public ChunkOpsResult edit(String chunkId, String tenantId, String newContent) {
        OwnedChunk owned = loadOwned(chunkId, tenantId);
        KbChunk chunk = owned.chunk();
        if (Boolean.TRUE.equals(chunk.getIsDeleted())) {
            throw chunkNotFound(chunkId); // 软删 chunk 编辑面不可见，先恢复
        }
        guardNotProcessing(owned.doc(), chunkId);

        // 与 ETL 入库链同源消毒：掩码落库 + 注入打标（不阻断，S4 定案）
        Document sanitized = sanitizingTransformer
            .apply(List.of(new Document(newContent))).get(0);
        String content = sanitized.getText();
        boolean injectionHit = Boolean.TRUE.equals(
            sanitized.getMetadata().get(SanitizingTransformer.INJECTION_HIT_KEY));

        chunk.setContent(content);
        chunk.setTokenCount((int) (content.length() / 2.5));
        chunk.setMetadata(mergeInjectionHit(chunk.getMetadata(), injectionHit));
        chunk.setHeadingPath(headingPathFromMetadata(chunk.getMetadata()));
        chunkRepository.save(chunk);
        metrics.recordChunkOps("edit");
        log.info("Chunk 编辑完成: chunkId={}, docId={}, injectionHit={}",
            chunkId, chunk.getDocId(), injectionHit);
        // 语义缓存按文档失效（簇③ 5.6 批2）：内容已变更，引用该文档的缓存回答即失效
        cacheInvalidationPublisher.ifAvailable(publisher -> publisher.publish(tenantId, chunk.getDocId()));

        etlExecutor.execute(() -> reembedAndIndex(chunk, owned.doc()));
        return new ChunkOpsResult(chunk, true);
    }

    /**
     * 软删除：委派 C1 软删管道（PG is_deleted=true + ES markDeleted + 向量物理删）。
     * 幂等：已软删 chunk 重复调用静默成功。
     */
    public ChunkOpsResult softDelete(String chunkId, String tenantId) {
        OwnedChunk owned = loadOwned(chunkId, tenantId);
        KbChunk chunk = owned.chunk();
        if (Boolean.TRUE.equals(chunk.getIsDeleted())) {
            return new ChunkOpsResult(chunk, false); // 幂等：已软删
        }
        guardNotProcessing(owned.doc(), chunkId);

        KbChunk deleted = chunkCleanupService.softDelete(chunkId);
        metrics.recordChunkOps("soft_delete");
        // 语义缓存按文档失效（簇③ 5.6 批2）：软删后证据面收缩，既有缓存回答保守失效
        cacheInvalidationPublisher.ifAvailable(publisher -> publisher.publish(tenantId, chunk.getDocId()));
        return new ChunkOpsResult(deleted != null ? deleted : chunk, true);
    }

    /**
     * 恢复软删 chunk：PG is_deleted=false + 异步重嵌入（软删时向量已物理删，
     * 恢复必经重嵌入；ES 经 indexChunks 覆写重置 is_deleted=false）。
     */
    public ChunkOpsResult restore(String chunkId, String tenantId) {
        OwnedChunk owned = loadOwned(chunkId, tenantId);
        KbChunk chunk = owned.chunk();
        if (!Boolean.TRUE.equals(chunk.getIsDeleted())) {
            throw new BusinessException("CHUNK_NOT_DELETED",
                "Chunk 未处于软删状态，无需恢复: " + chunkId);
        }
        guardNotProcessing(owned.doc(), chunkId);

        chunk.setIsDeleted(false);
        chunk.setHeadingPath(headingPathFromMetadata(chunk.getMetadata()));
        chunkRepository.save(chunk);
        metrics.recordChunkOps("restore");
        log.info("Chunk 恢复完成: chunkId={}, docId={}", chunkId, chunk.getDocId());
        // 语义缓存按文档失效（簇③ 5.6 批2）：恢复后证据面扩张，既有缓存回答保守失效
        cacheInvalidationPublisher.ifAvailable(publisher -> publisher.publish(tenantId, chunk.getDocId()));

        etlExecutor.execute(() -> reembedAndIndex(chunk, owned.doc()));
        return new ChunkOpsResult(chunk, true);
    }

    // ── 内部方法 ──

    /** chunk + 所属文档装载与守卫：不存在/跨租户 → CHUNK_NOT_FOUND（fail-closed） */
    private OwnedChunk loadOwned(String chunkId, String tenantId) {
        KbChunk chunk = chunkRepository.findById(chunkId).orElse(null);
        if (chunk == null) {
            throw chunkNotFound(chunkId);
        }
        KbDocument doc = documentRepository.findById(chunk.getDocId()).orElse(null);
        if (doc == null || !tenantId.equals(doc.getTenantId())) {
            throw chunkNotFound(chunkId); // 跨租户不泄露存在性
        }
        return new OwnedChunk(chunk, doc);
    }

    /** 文档处理中守卫：与在途 ETL 竞态会互覆 chunk → DOC_NOT_READY（409） */
    private static void guardNotProcessing(KbDocument doc, String chunkId) {
        if (doc.getStatus() != null && doc.getStatus().isProcessing()) {
            throw new BusinessException("DOC_NOT_READY",
                "文档处理中，禁止 Chunk 运维操作（当前 " + doc.getStatus() + "）: chunkId=" + chunkId);
        }
    }

    /**
     * 异步重嵌入：向量库 delete → add 两步（Milvus add 非 upsert，源码实证）+
     * ES 全文档覆写（indexChunks 写 is_deleted=false，软删恢复同路径收敛）。
     * 从属副本语义：失败仅告警，PG 事实源已更新，重建（4.5）兜底。
     */
    private void reembedAndIndex(KbChunk chunk, KbDocument doc) {
        String chunkId = chunk.getId();
        try {
            vectorStore.delete(List.of(chunkId));
            vectorStore.add(List.of(new Document(
                chunkId, chunk.getContent(), DocumentEtlService.vectorMetadata(chunk, doc))));
            esIndexWriter.indexChunks(doc, List.of(chunk));
            log.info("Chunk 重嵌入完成: chunkId={}, docId={}", chunkId, doc.getId());
        } catch (Exception e) {
            log.error("Chunk 重嵌入失败（PG 已更新，重建兜底）: chunkId={}, docId={}, error={}",
                chunkId, doc.getId(), e.getMessage(), e);
        }
    }

    /** metadata JSONB 注入标记合并：保留既有键（heading_path 等），仅增删 injection_hit */
    private String mergeInjectionHit(String metadataJson, boolean injectionHit) {
        Map<String, Object> meta = parseMetadata(metadataJson);
        if (injectionHit) {
            meta.put(SanitizingTransformer.INJECTION_HIT_KEY, true);
        } else {
            meta.remove(SanitizingTransformer.INJECTION_HIT_KEY);
        }
        try {
            return jsonMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("chunk metadata 序列化失败，回退空对象: {}", e.getMessage());
            return "{}";
        }
    }

    /** metadata JSONB 解析 heading_path（@Transient 载体回填，供向量元数据/ES 消费） */
    private String headingPathFromMetadata(String metadataJson) {
        Object value = parseMetadata(metadataJson).get(HtmlProtectingSplitter.HEADING_PATH_KEY);
        return value instanceof String hp && !hp.isBlank() ? hp : null;
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = jsonMapper.readValue(metadataJson, Map.class);
            return new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            log.warn("chunk metadata 解析失败，按空对象处理: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static BusinessException chunkNotFound(String chunkId) {
        return new BusinessException("CHUNK_NOT_FOUND", "Chunk 不存在: " + chunkId);
    }

    /** 所有权校验通过的 chunk + 文档对 */
    record OwnedChunk(KbChunk chunk, KbDocument doc) {
    }

    /** 运维结果：实体 + 是否实际执行（false = 幂等空转，如重复软删） */
    public record ChunkOpsResult(KbChunk chunk, boolean applied) {
    }
}
