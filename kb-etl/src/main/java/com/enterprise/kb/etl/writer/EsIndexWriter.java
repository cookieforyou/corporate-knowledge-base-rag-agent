package com.enterprise.kb.etl.writer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.etl.service.DocumentEtlService;
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
     * 批量写入文档的全部 Chunk。
     *
     * <p>刷新策略 {@code wait_for}（v2.19 簇③ D2）：请求挂起至下一次刷新周期完成、
     * 写入可检索后返回——语义上仍保证「返回即可检索」，但避免大文档 ETL 尾部每批
     * 强制全索引刷新（{@code true}）的长尾延迟；原 {@code Refresh.True} 强刷收编废弃。
     */
    public void indexChunks(KbDocument doc, List<KbChunk> entities) {
        if (entities.isEmpty()) return;
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.WaitFor);
            for (KbChunk e : entities) {
                EsChunkDoc docModel = EsChunkDoc.builder()
                    .chunkId(e.getId())
                    .docId(doc.getId())
                    .tenantId(doc.getTenantId())
                    .content(e.getContent())
                    .chunkType(e.getChunkType() != null ? e.getChunkType().name() : "TEXT")
                    .headingPath(e.getHeadingPath())
                    .fileName(doc.getOriginalName())
                    .pageNum(e.getPageNum())
                    .isDeleted(false)
                    // 注入打标（安全簇④ D2）：命中时写 true，未命中 null（序列化缺省，索引瘦身）
                    .injectionHit(DocumentEtlService.injectionHitOf(e.getMetadata()) ? Boolean.TRUE : null)
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
     * 查询指定文档在 ES 中的全部 chunk _id（Phase 4 簇③ 4.5 重建 ES 孤儿清扫）。
     *
     * <p>重建漂移收敛语义：蓝绿管线以 PG 为事实源全量重写，ES 中「PG 已无对应行」
     * 的残留 doc 即孤儿——调用方以 PG chunk ID 集 diff 后经
     * {@link #deleteByChunkIds(List)} 精确清除（不走 deleteByDocId 全清，
     * 避免误删刚重写完成的存活 doc）。
     *
     * <p>尽力而为：查询失败返回空列表（孤儿清扫跳过，不误删）；单文档 chunk 规模
     * 上限取 ES 默认 max_result_window（10000），超限文档的尾部孤儿留待下轮重建。
     */
    public List<String> findChunkIdsByDocId(String docId) {
        try {
            var response = esClient.search(s -> s
                    .index(EsChunkDoc.INDEX)
                    .query(q -> q.term(t -> t.field("doc_id").value(docId)))
                    .size(10_000)
                    .source(sc -> sc.fetch(false)),
                EsChunkDoc.class);
            return response.hits().hits().stream()
                .map(co.elastic.clients.elasticsearch.core.search.Hit::id)
                .toList();
        } catch (Exception e) {
            log.warn("ES 按文档查询失败（孤儿清扫跳过，不误删）: docId={}, error={}", docId, e.getMessage());
            return List.of();
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
     * 按 chunkId 批量物理删除（簇⑥ C1 蓝绿重入库 diff 清理）。
     *
     * <p>与 {@link #deleteByDocId(String)} 同为运维路径 {@code refresh(true)} 强刷
     * （删除即时可见性优先）；从属副本语义不变——失败仅告警不阻断，
     * 且「ES 缺失」方向安全（BM25 路检索不到 > 残留旧版本）。
     * bulk 中 not_found 结果视为幂等成功（目标本就不在 ES）。
     */
    public void deleteByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        try {
            BulkRequest.Builder bulk = new BulkRequest.Builder().refresh(Refresh.True);
            for (String chunkId : chunkIds) {
                bulk.operations(op -> op
                    .delete(del -> del.index(EsChunkDoc.INDEX).id(chunkId)));
            }
            BulkResponse response = esClient.bulk(bulk.build());
            if (response.errors()) {
                long realFailed = response.items().stream()
                    .filter(i -> i.error() != null && !"not_found".equals(i.error().type()))
                    .count();
                if (realFailed > 0) {
                    List<String> failedIds = response.items().stream()
                        .filter(i -> i.error() != null && !"not_found".equals(i.error().type()))
                        .map(BulkResponseItem::id).limit(5).toList();
                    log.error("ES 按 ID 删除部分失败: failed={}/{}, 示例 chunkId={}",
                        realFailed, chunkIds.size(), failedIds);
                }
            } else {
                log.info("ES 按 ID 删除完成: chunks={}", chunkIds.size());
            }
        } catch (Exception e) {
            log.error("ES 按 ID 删除异常（不阻断）: chunks={}, error={}", chunkIds.size(), e.getMessage());
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
