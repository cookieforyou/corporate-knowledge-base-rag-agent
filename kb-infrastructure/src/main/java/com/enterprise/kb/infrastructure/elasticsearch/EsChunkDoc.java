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

    /**
     * 标题路径（簇④ A4，9.2 v2.21），如「产品手册 &gt; 定价」。展示与后续检索两用；
     * 新建索引经 mapping 走 ik 分词，存量索引经 dynamic mapping 自动映射
     * （完全对齐需 Phase 4.6 索引重建窗口）。
     */
    @JsonProperty("heading_path")
    private String headingPath;

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
