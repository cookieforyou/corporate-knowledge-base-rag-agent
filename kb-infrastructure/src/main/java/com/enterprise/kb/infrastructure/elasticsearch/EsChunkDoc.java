package com.enterprise.kb.infrastructure.elasticsearch;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * kb_chunks 索引文档模型 —— ETL 双写（kb-etl EsIndexWriter）与
 * BM25 检索（kb-ai-core ElasticsearchDocumentRetriever，2.6）共用
 *
 * <p>字段定义与第七章 7.4 / 第九章 9.4 / 第十章 10.3 严格一致（snake_case，
 * ik 双模式分词）。文档 _id = chunkId（与 kb_chunk.id / 向量库 vectorId 同源，RRF 融合键）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EsChunkDoc {

    public static final String INDEX = "kb_chunks";

    @JsonProperty("chunk_id")
    private String chunkId;

    @JsonProperty("doc_id")
    private String docId;

    @JsonProperty("tenant_id")
    private String tenantId;

    /** 正文，ik_max_word 索引 / ik_smart 检索 */
    @JsonProperty("content")
    private String content;

    @JsonProperty("chunk_type")
    private String chunkType;

    @JsonProperty("file_name")
    private String fileName;

    @JsonProperty("page_num")
    private Integer pageNum;

    @JsonProperty("is_deleted")
    private Boolean isDeleted;

    /** ISO-8601 字符串（LocalDateTime.toString()），ES date 类型兼容 strict_date_optional_time */
    @JsonProperty("created_at")
    private String createdAt;
}
