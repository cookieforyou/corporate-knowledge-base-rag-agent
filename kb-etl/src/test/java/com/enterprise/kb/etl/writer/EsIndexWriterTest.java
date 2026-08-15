package com.enterprise.kb.etl.writer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.infrastructure.elasticsearch.EsChunkDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EsIndexWriter 单测（簇⑥ C1 补零测盲区）：
 * 从属副本语义——双写/删除失败只告警不阻断；bulk not_found 视为幂等成功。
 */
class EsIndexWriterTest {

    private ElasticsearchClient esClient;
    private EsIndexWriter writer;

    @BeforeEach
    void setUp() {
        esClient = mock(ElasticsearchClient.class);
        writer = new EsIndexWriter(esClient);
    }

    private static KbDocument doc() {
        KbDocument doc = new KbDocument();
        doc.setId("doc-1");
        doc.setTenantId("t-1");
        doc.setOriginalName("手册.md");
        return doc;
    }

    private static KbChunk chunk(String id) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setContent("内容-" + id);
        c.setChunkType(ChunkType.TEXT);
        return c;
    }

    @Test
    void indexChunksSwallowsException() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(new RuntimeException("ES 不可达"));
        assertThatCode(() -> writer.indexChunks(doc(), List.of(chunk("c-1"))))
            .doesNotThrowAnyException();
    }

    @Test
    void indexChunksEmptyListSkipsClient() throws Exception {
        writer.indexChunks(doc(), List.of());
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void markDeletedUpdatesIsDeletedFlag() throws Exception {
        assertThatCode(() -> writer.markDeleted("c-1")).doesNotThrowAnyException();
        verify(esClient).update(any(Function.class), eq(EsChunkDoc.class));
    }

    @Test
    void deleteByDocIdSwallowsException() throws Exception {
        when(esClient.deleteByQuery(any(Function.class))).thenThrow(new RuntimeException("ES 不可达"));
        assertThatCode(() -> writer.deleteByDocId("doc-1")).doesNotThrowAnyException();
    }

    @Test
    void deleteByDocIdZeroMatchDoesNotThrow() throws Exception {
        DeleteByQueryResponse resp = mock(DeleteByQueryResponse.class);
        when(resp.deleted()).thenReturn(0L);
        when(esClient.deleteByQuery(any(Function.class))).thenReturn(resp);
        assertThatCode(() -> writer.deleteByDocId("doc-1")).doesNotThrowAnyException();
    }

    @Test
    void deleteByChunkIdsEmptySkipsClient() throws Exception {
        writer.deleteByChunkIds(List.of());
        writer.deleteByChunkIds(null);
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void deleteByChunkIdsNotFoundIsIdempotentSuccess() throws Exception {
        BulkResponse resp = mock(BulkResponse.class);
        BulkResponseItem item = mock(BulkResponseItem.class);
        ErrorCause cause = mock(ErrorCause.class);
        when(cause.type()).thenReturn("not_found");
        when(item.error()).thenReturn(cause);
        when(item.id()).thenReturn("c-gone");
        when(resp.errors()).thenReturn(true);
        when(resp.items()).thenReturn(List.of(item));
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(resp);

        assertThatCode(() -> writer.deleteByChunkIds(List.of("c-gone"))).doesNotThrowAnyException();
    }

    @Test
    void deleteByChunkIdsSwallowsException() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(new RuntimeException("ES 不可达"));
        assertThatCode(() -> writer.deleteByChunkIds(List.of("c-1", "c-2")))
            .doesNotThrowAnyException();
    }

    // ── 簇③ 4.5：重建 ES 孤儿清扫的 doc_id 查询 ──

    @SuppressWarnings("unchecked")
    @Test
    void findChunkIdsReturnsHitIds() throws Exception {
        SearchResponse<EsChunkDoc> resp = mock(SearchResponse.class);
        HitsMetadata<EsChunkDoc> hitsMeta = mock(HitsMetadata.class);
        Hit<EsChunkDoc> h1 = mock(Hit.class);
        Hit<EsChunkDoc> h2 = mock(Hit.class);
        when(h1.id()).thenReturn("c-1");
        when(h2.id()).thenReturn("c-2");
        when(hitsMeta.hits()).thenReturn(List.of(h1, h2));
        when(resp.hits()).thenReturn(hitsMeta);
        when(esClient.search(any(Function.class), eq(EsChunkDoc.class))).thenReturn(resp);

        assertThat(writer.findChunkIdsByDocId("doc-1")).containsExactly("c-1", "c-2");
    }

    /** 查询失败返回空列表——孤儿清扫跳过而非误删（尽力而为语义） */
    @SuppressWarnings("unchecked")
    @Test
    void findChunkIdsSwallowsFailureAsEmpty() throws Exception {
        when(esClient.search(any(Function.class), eq(EsChunkDoc.class)))
            .thenThrow(new RuntimeException("ES 不可达"));
        assertThat(writer.findChunkIdsByDocId("doc-1")).isEmpty();
    }
}
