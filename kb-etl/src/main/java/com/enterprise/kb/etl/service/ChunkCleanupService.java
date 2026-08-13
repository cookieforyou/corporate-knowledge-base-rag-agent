package com.enterprise.kb.etl.service;

import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Chunk 三库级联清理组件（簇⑥ C1）—— PG kb_chunk / 向量库 / ES 的统一清理原语。
 *
 * <p><b>共享基座</b>：文档删除级联（DocumentService.delete）、蓝绿重入库 diff 清理
 * （DocumentEtlService）与未来 Phase 4.5 物理删除级联 / 4.6 索引重建复用同一组件，
 * 避免三库清理逻辑散点漂移（优化报告 C1「先做 C1 为 4.4-4.6 铺路」）。
 *
 * <p><b>一致性模型</b>（9.4）：PG kb_chunk 是唯一事实源——PG 删除为必须项，
 * 向量库/ES 清理为尽力而为（失败告警不阻断：ES 缺失方向安全（检索不到），
 * 向量残留可离线清理/索引重建兜底）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkCleanupService {

    private final KbChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final EsIndexWriter esIndexWriter;

    /**
     * 物理级联删除指定 chunk（三库）。
     *
     * @param docId       所属文档（仅日志上下文）
     * @param chunkIds    待删 chunkId 集（= vectorId = ES _id，9.3 不变量）
     * @param esByDocId   ES 清理形态：true = deleteByQuery(doc_id) 文档级兜底扫尾
     *                    （含 PG 外的 ES 孤儿，文档整体删除用）；false = 按 chunkIds
     *                    精确 bulk 删除（蓝绿 diff 清理用，不可误删同文档存活 chunk）
     */
    public void physicalDelete(String docId, List<String> chunkIds, boolean esByDocId) {
        if (chunkIds != null && !chunkIds.isEmpty()) {
            chunkRepository.deleteAllById(chunkIds);
            try {
                vectorStore.delete(chunkIds);
            } catch (Exception e) {
                log.warn("向量库清理失败（不阻断）: docId={}, chunks={}, {}",
                    docId, chunkIds.size(), e.getMessage());
            }
        }
        try {
            if (esByDocId) {
                esIndexWriter.deleteByDocId(docId);
            } else if (chunkIds != null && !chunkIds.isEmpty()) {
                esIndexWriter.deleteByChunkIds(chunkIds);
            }
        } catch (Exception e) {
            log.warn("ES 清理失败（不阻断）: docId={}, {}", docId, e.getMessage());
        }
    }

    /**
     * Chunk 软删除管道（簇⑥ C1「只通管道不建门面」，REST 门面归 Phase 4.4 Chunk 运维 API）：
     * PG is_deleted=true + ES is_deleted=true（markDeleted）+ 向量库物理删除。
     *
     * <p><b>向量库无软删形态</b>：检索双路均过滤 is_deleted（ES term filter +
     * RetrievalContext FilterExpression），软删 chunk 本已检索不可见；向量库不留
     * 死数据，恢复（4.4 门面职责）需经重嵌入。PG 事实源保留行即软删语义本体。
     *
     * @return 软删成功后的实体；chunk 不存在返回 null
     */
    public KbChunk softDelete(String chunkId) {
        KbChunk chunk = chunkRepository.findById(chunkId).orElse(null);
        if (chunk == null) {
            log.warn("软删除目标不存在: chunkId={}", chunkId);
            return null;
        }
        chunk.setIsDeleted(true);
        chunkRepository.save(chunk);
        esIndexWriter.markDeleted(chunkId);
        try {
            vectorStore.delete(List.of(chunkId));
        } catch (Exception e) {
            log.warn("软删除向量清理失败（不阻断）: chunkId={}, {}", chunkId, e.getMessage());
        }
        log.info("Chunk 软删除完成: chunkId={}, docId={}", chunkId, chunk.getDocId());
        return chunk;
    }
}
