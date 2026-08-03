package com.enterprise.kb.etl.writer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.infrastructure.elasticsearch.EsChunkDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES 双写器（设计文档 9.4）—— chunk 与向量库同批写入 kb_chunks 索引
 *
 * <p>一致性模型：PG kb_chunk 是唯一事实源，ES 与向量库均为从属副本。
 * 因此双写失败**不抛异常、不阻断 ETL 主流程**，仅记录失败明细，
 * 由索引重建任务（第十四章，Phase 4）兜底修复漂移。
 *
 * <p>幂等性：文档 _id = chunkId，重复写入即覆盖，ETL 重试安全。
 */
@Slf4j
@Component
public class EsIndexWriter {

    private final ElasticsearchClient esClient;

    public EsIndexWriter(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * 批量写入文档的全部 Chunk（refresh=true 保证写入后立即可检索）
     */
    public void indexChunks(KbDocument doc, List<KbChunk> entities) {
        if (entities.isEmpty()) return;
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.True);
            for (KbChunk e : entities) {
                EsChunkDoc docModel = EsChunkDoc.builder()
                    .chunkId(e.getId())
                    .docId(doc.getId())
                    .tenantId(doc.getTenantId())
                    .content(e.getContent())
                    .chunkType(e.getChunkType() != null ? e.getChunkType().name() : "TEXT")
                    .fileName(doc.getOriginalName())
                    .pageNum(e.getPageNum())
                    .isDeleted(false)
                    .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null)
                    .build();
                bulk.operations(op -> op
                    .index(idx -> idx.index(EsChunkDoc.INDEX).id(e.getId()).document(docModel)));
            }

            BulkResponse response = esClient.bulk(bulk.build());
            if (response.errors()) {
                long failed = response.items().stream()
                    .filter(i -> i.error() != null).count();
                List<String> failedIds = response.items().stream()
                    .filter(i -> i.error() != null)
                    .map(BulkResponseItem::id).limit(5).toList();
                log.error("ES 双写部分失败: docId={}, failed={}/{}, 示例失败 chunkId={}",
                    doc.getId(), failed, entities.size(), failedIds);
            } else {
                log.info("ES 双写完成: docId={}, chunks={}", doc.getId(), entities.size());
            }
        } catch (Exception e) {
            // 从属副本写入失败不阻断 ETL（PG 为事实源，索引重建可兜底）
            log.error("ES 双写异常（不阻断 ETL）: docId={}, error={}", doc.getId(), e.getMessage(), e);
        }
    }

    /**
     * 软删除同步（Chunk 编辑/删除运维 API 调用，第十四章）
     */
    public void markDeleted(String chunkId) {
        try {
            esClient.update(u -> u
                    .index(EsChunkDoc.INDEX)
                    .id(chunkId)
                    .doc(java.util.Map.of("is_deleted", true)),
                EsChunkDoc.class);
        } catch (Exception e) {
            log.error("ES 软删除失败: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    /**
     * 文档物理删除时级联清理（第十四章）
     *
     * <p>2026-08-03 修复：查询字段曾为 camelCase "docId"——与 2.6 snake_case 对齐
     * （索引字段 doc_id）不一致，term 查询恒空匹配、0 删除却按成功记录，
     * E2E 级联删除测试发现 ES 残留。零匹配改为 WARN，静默失败可观测。
     */
    public void deleteByDocId(String docId) {
        try {
            var response = esClient.deleteByQuery(d -> d
                .index(EsChunkDoc.INDEX)
                .query(q -> q.term(t -> t.field("doc_id").value(docId)))
                .refresh(true));
            if (response.deleted() != null && response.deleted() == 0) {
                log.warn("ES 级联删除匹配 0 条: docId={}（该文档可能无 ES 数据；若预期有数据请排查索引一致性）", docId);
            } else {
                log.info("ES 级联删除: docId={}, deleted={}", docId, response.deleted());
            }
        } catch (Exception e) {
            log.error("ES 级联删除失败: docId={}, error={}", docId, e.getMessage());
        }
    }
}
