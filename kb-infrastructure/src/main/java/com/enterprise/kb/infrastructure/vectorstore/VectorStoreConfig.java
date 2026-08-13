package com.enterprise.kb.infrastructure.vectorstore;

import com.enterprise.kb.infrastructure.vectorstore.KbVectorStoreProperties.Pgvector;
import com.enterprise.kb.infrastructure.vectorstore.KbVectorStoreProperties.Milvus;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 向量库条件装配配置
 *
 * <p>禁用 Spring AI 原生 auto-config（spring.ai.vectorstore.type=custom），
 * 改由本配置类根据 {@code kb.vector-store.provider} 条件创建对应的 VectorStore Bean。</p>
 *
 * <ul>
 *   <li>{@code kb.vector-store.provider=pgvector} → {@link PgVectorStore}</li>
 *   <li>{@code kb.vector-store.provider=milvus}（默认）→ {@link MilvusVectorStore}</li>
 * </ul>
 *
 * <p>上层模块（kb-ai-core、kb-etl）注入 {@link VectorStore} 接口，无需感知底层实现。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(KbVectorStoreProperties.class)
public class VectorStoreConfig {

    /**
     * PgVectorStore — 当 {@code kb.vector-store.provider=pgvector} 时激活。
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kb.vector-store",
        name = "provider",
        havingValue = "pgvector",
        matchIfMissing = false
    )
    public VectorStore pgvectorVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            KbVectorStoreProperties props) {

        Pgvector cfg = props.getPgvector();
        log.info("创建 PgVectorStore → schema={}, table={}, dims={}, distance={}, index={}",
            cfg.getSchemaName(), cfg.getTableName(), cfg.getDimensions(),
            cfg.getDistanceType(), cfg.getIndexType());

        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .vectorTableName(cfg.getTableName())
            .schemaName(cfg.getSchemaName())
            .dimensions(cfg.getDimensions())
            .distanceType(PgVectorStore.PgDistanceType.valueOf(cfg.getDistanceType()))
            .indexType(PgVectorStore.PgIndexType.valueOf(cfg.getIndexType()))
            .initializeSchema(cfg.isInitializeSchema())
            // kb_embeddings.id 为 VARCHAR(36) 融合键（07 章 schema，chunk id = 确定性
            // nameUUID 字符串）；PgVectorStore 默认 idType=UUID 时 delete 以 uuid 参数
            // 匹配 varchar 列静默失配（insert 走隐式转换能写入），须钉 TEXT 形态
            // （簇⑥ D3 集成测试实证，2026-08-13）
            .idType(PgVectorStore.PgIdType.TEXT)
            .build();
    }

    /**
     * MilvusVectorStore — 当 {@code kb.vector-store.provider=milvus} 或未设置时激活。
     * <p>{@code matchIfMissing=true} 保证向后兼容：不配置时默认使用 Milvus。</p>
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "kb.vector-store",
        name = "provider",
        havingValue = "milvus",
        matchIfMissing = true
    )
    public VectorStore milvusVectorStore(
            EmbeddingModel embeddingModel,
            KbVectorStoreProperties props) {

        Milvus cfg = props.getMilvus();
        MilvusServiceClient milvusClient = new MilvusServiceClient(
            ConnectParam.newBuilder()
                .withHost(cfg.getHost())
                .withPort(cfg.getPort())
                .withDatabaseName(cfg.getDatabaseName())
                .withAuthorization(cfg.getUsername(), cfg.getPassword())
                .build());
        log.info("创建 MilvusVectorStore → db={}, collection={}, dims={}, index={}, metric={}",
            cfg.getDatabaseName(), cfg.getCollectionName(),
            cfg.getEmbeddingDimension(), cfg.getIndexType(), cfg.getMetricType());

        return MilvusVectorStore.builder(milvusClient, embeddingModel)
            .collectionName(cfg.getCollectionName())
            .databaseName(cfg.getDatabaseName())
            .embeddingDimension(cfg.getEmbeddingDimension())
            .indexType(IndexType.valueOf(cfg.getIndexType()))
            .metricType(MetricType.valueOf(cfg.getMetricType()))
            .initializeSchema(true)
            .build();
    }
}
