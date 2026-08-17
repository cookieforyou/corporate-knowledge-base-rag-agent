package com.enterprise.kb.etl.service;

import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量库文档元数据契约测试（Phase 4 簇③）——ETL 入库与 Chunk 运维重嵌入
 * 共享的单一契约（DocumentEtlService.vectorMetadata）：检索 FilterExpression
 * 消费的 tenant_id/is_deleted 与调试台展示的 file_name/page_num/heading_path。
 */
class VectorMetadataContractTest {

    private static KbChunk chunk() {
        KbChunk chunk = new KbChunk();
        chunk.setId("c-1");
        chunk.setChunkType(ChunkType.TABLE);
        chunk.setPageNum(3);
        chunk.setIsDeleted(false);
        chunk.setHeadingPath("一级 > 二级");
        return chunk;
    }

    private static KbDocument doc() {
        KbDocument doc = new KbDocument();
        doc.setId("doc-1");
        doc.setTenantId("t-1");
        doc.setName("手册.pdf");
        return doc;
    }

    @Test
    void fullFieldMapping() {
        Map<String, Object> meta = DocumentEtlService.vectorMetadata(chunk(), doc());

        assertThat(meta)
            .containsEntry("chunk_id", "c-1")
            .containsEntry("doc_id", "doc-1")
            .containsEntry("tenant_id", "t-1")
            .containsEntry("chunk_type", "TABLE")
            .containsEntry("file_name", "手册.pdf")
            .containsEntry("page_num", 3)
            .containsEntry("is_deleted", false)
            .containsEntry("heading_path", "一级 > 二级");
    }

    /** 可空字段兜底：元数据禁 null（Spring AI 约束，坑位④），缺省写兜底值或不写键 */
    @Test
    void nullFieldsFallBackWithoutNullValues() {
        KbChunk bare = new KbChunk();
        bare.setId("c-2");
        KbDocument unnamed = new KbDocument();
        unnamed.setId("doc-2");
        unnamed.setTenantId("t-1");

        Map<String, Object> meta = DocumentEtlService.vectorMetadata(bare, unnamed);

        assertThat(meta)
            .containsEntry("chunk_type", "TEXT")
            .containsEntry("file_name", "unknown")
            .containsEntry("page_num", 0)
            .containsEntry("is_deleted", false)
            .doesNotContainKey("heading_path")
            .doesNotContainKey("injection_hit");
        assertThat(meta.values()).doesNotContainNull();
    }

    // ── 注入打标传播（安全簇④ D2：PG 事实源 JSONB → 向量元数据，检索侧降权消费）──

    @Test
    void injectionHitTruePropagatedToVectorMetadata() {
        KbChunk hit = chunk();
        hit.setMetadata("{\"injection_hit\":true,\"heading_path\":\"一级 > 二级\"}");

        Map<String, Object> meta = DocumentEtlService.vectorMetadata(hit, doc());

        assertThat(meta).containsEntry("injection_hit", true);
        assertThat(meta).containsEntry("heading_path", "一级 > 二级");   // 既有键不受影响
    }

    @Test
    void injectionHitAbsentOrFalseKeepsKeyAbsent() {
        KbChunk absent = chunk();
        absent.setMetadata("{}");
        KbChunk explicitFalse = chunk();
        explicitFalse.setMetadata("{\"injection_hit\":false}");

        assertThat(DocumentEtlService.vectorMetadata(absent, doc())).doesNotContainKey("injection_hit");
        assertThat(DocumentEtlService.vectorMetadata(explicitFalse, doc())).doesNotContainKey("injection_hit");
    }

    @Test
    void injectionHitOfParsesJsonbAndFailsSafe() {
        assertThat(DocumentEtlService.injectionHitOf("{\"injection_hit\":true}")).isTrue();
        assertThat(DocumentEtlService.injectionHitOf("{\"injection_hit\":false}")).isFalse();
        assertThat(DocumentEtlService.injectionHitOf("{}")).isFalse();
        assertThat(DocumentEtlService.injectionHitOf("")).isFalse();
        assertThat(DocumentEtlService.injectionHitOf(null)).isFalse();
        // 非法 JSON fail-safe：降权不生效 = 默认关现状（最坏无副作用）
        assertThat(DocumentEtlService.injectionHitOf("not-json")).isFalse();
    }
}
