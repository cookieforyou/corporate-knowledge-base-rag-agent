package com.enterprise.kb.infrastructure.vectorstore;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量库配置属性（prefix = kb.vector-store）
 *
 * <p>支持 pgvector 和 Milvus 两种后端，通过 {@code provider} 属性切换。</p>
 *
 * <pre>
 * kb:
 *   vector-store:
 *     provider: milvus
 *     pgvector:
 *       table-name: kb_embeddings
 *       dimensions: 1024
 *     milvus:
 *       host: localhost
 *       port: 19530
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "kb.vector-store")
public class KbVectorStoreProperties {

    /** 当前使用的向量库提供商（默认 milvus） */
    private VectorStoreProvider provider = VectorStoreProvider.MILVUS;

    /** pgvector 配置 */
    private Pgvector pgvector = new Pgvector();

    /** Milvus 配置 */
    private Milvus milvus = new Milvus();

    // ──────────── 内嵌配置类 ────────────

    @Data
    public static class Pgvector {

        /** 向量表名 */
        private String tableName = "kb_embeddings";

        /** 数据库 schema */
        private String schemaName = "public";

        /** 向量维度（需与 EmbeddingModel 输出维度一致） */
        private int dimensions = 1024;

        /** 距离算法：COSINE_DISTANCE | EUCLIDEAN_DISTANCE | NEGATIVE_INNER_PRODUCT */
        private String distanceType = "COSINE_DISTANCE";

        /** 索引类型：HNSW | IVFFLAT */
        private String indexType = "HNSW";

        /** 是否自动初始化（首次启动创建 vector 扩展和表） */
        private boolean initializeSchema = true;
    }

    @Data
    public static class Milvus {

        /** Milvus 服务地址 */
        private String host = "localhost";

        /** Milvus 服务端口 */
        private int port = 19530;

        /** 用户名 */
        private String username = "";

        /** 密码 */
        private String password = "";

        /** 数据库名 */
        private String databaseName = "kb_rag_agent";

        /** 集合名 */
        private String collectionName = "kb_chunks";

        /** 向量维度 */
        private int embeddingDimension = 1024;

        /** 索引类型 */
        private String indexType = "HNSW";

        /** 度量类型：COSINE | L2 | IP */
        private String metricType = "COSINE";
    }
}
