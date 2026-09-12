package com.enterprise.kb.ai.retriever;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.infrastructure.elasticsearch.EsChunkDoc;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ES Hit → Document 映射单测（纯映射逻辑，无 ES 依赖）
 */
class ElasticsearchDocumentRetrieverTest {

    private final ElasticsearchDocumentRetriever retriever =
        new ElasticsearchDocumentRetriever(null);

    private Hit<EsChunkDoc> hit(EsChunkDoc source, double score) {
        return Hit.of(b -> b
            .index(EsChunkDoc.INDEX)
            .id(source.getChunkId())
            .score(score)
            .source(source));
    }

    @Test
    void toDocument_mapsIdScoreAndRank() {
        EsChunkDoc src = EsChunkDoc.builder()
            .chunkId("chunk-001").docId("doc-9").tenantId("t-1")
            .content("增值税发票认证期限").chunkType("TEXT")
            .fileName("发票手册.pdf").pageNum(12).isDeleted(false)
            .build();

        Document doc = retriever.toDocument(hit(src, 8.75), 2);

        // 融合键与得分
        assertEquals("chunk-001", doc.getId());
        assertEquals(8.75, doc.getScore());
        assertEquals("增值税发票认证期限", doc.getText());

        // 10.1 元数据约定
        assertEquals("chunk-001", doc.getMetadata().get(Constants.Retrieval.META_CHUNK_ID));
        assertEquals("doc-9", doc.getMetadata().get(Constants.Retrieval.META_DOC_ID));
        assertEquals("t-1", doc.getMetadata().get("tenant_id"));
        assertEquals("TEXT", doc.getMetadata().get("chunk_type"));
        assertEquals("发票手册.pdf", doc.getMetadata().get("file_name"));
        assertEquals(12, doc.getMetadata().get("page_num"));
        assertEquals(8.75, doc.getMetadata().get(Constants.Retrieval.META_BM25_SCORE));
        assertEquals(2, doc.getMetadata().get(Constants.Retrieval.ROUTE_BM25 + Constants.Retrieval.RANK_KEY_SUFFIX));
        assertEquals(Constants.Retrieval.ROUTE_BM25, doc.getMetadata().get(Constants.Retrieval.META_RETRIEVAL_SOURCE));
    }

    @Test
    void toDocument_defaultsChunkTypeWhenMissing() {
        EsChunkDoc src = EsChunkDoc.builder()
            .chunkId("chunk-002").docId("doc-9").tenantId("t-1")
            .content("表格内容").chunkType(null)
            .build();

        Document doc = retriever.toDocument(hit(src, 1.0), 1);

        assertEquals("TEXT", doc.getMetadata().get("chunk_type"));
        // Spring AI metadata 禁止 null：可空字段缺省时不写入键
        assertFalse(doc.getMetadata().containsKey("page_num"));
        assertFalse(doc.getMetadata().containsKey("file_name"));
        assertFalse(doc.getMetadata().containsKey(Constants.Retrieval.INJECTION_HIT));
    }

    /** 注入打标透传（安全簇④ D2）：命中时写元数据供 RrfFusion 降权消费，缺省不写键 */
    @Test
    void toDocument_propagatesInjectionHitOnlyWhenTrue() {
        EsChunkDoc hitDoc = EsChunkDoc.builder()
            .chunkId("chunk-003").docId("doc-9").tenantId("t-1")
            .content("命中资料").injectionHit(Boolean.TRUE)
            .build();
        EsChunkDoc cleanDoc = EsChunkDoc.builder()
            .chunkId("chunk-004").docId("doc-9").tenantId("t-1")
            .content("干净资料").injectionHit(null)
            .build();

        Document hitMapped = retriever.toDocument(hit(hitDoc, 2.0), 1);
        Document cleanMapped = retriever.toDocument(hit(cleanDoc, 1.5), 2);

        assertEquals(Boolean.TRUE, hitMapped.getMetadata().get(Constants.Retrieval.INJECTION_HIT));
        assertFalse(cleanMapped.getMetadata().containsKey(Constants.Retrieval.INJECTION_HIT));
    }
}
