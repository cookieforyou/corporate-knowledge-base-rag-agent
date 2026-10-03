package com.enterprise.kb.infrastructure.graph;

import com.enterprise.kb.infrastructure.vectorstore.KbVectorStoreProperties;
import com.enterprise.kb.infrastructure.vectorstore.VectorStoreProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图谱 Schema 启动初始化（Phase5簇④）。
 *
 * <p>{@code rag.graph.enabled=true} 时于 ApplicationReady 执行连通性校验 +
 * 幂等 DDL（约束/索引/向量索引）。<b>失败不阻断应用启动</b>（图谱是检索增强件
 * 非事实源）——错误日志显形，运行期图路按单路容错降级为空（降级矩阵 10.2 同语义），
 * 对齐语义缓存「能力探测自关」先例的容错纪律。
 *
 * <p><b>维度对齐校验（v3.00）</b>：图向量维度是三处配置的共同契约（本模块常量
 * {@link GraphGateway#ENTITY_EMBEDDING_DIMENSIONS} × 向量库 {@code kb.vector-store.*} ×
 * 语义缓存 {@code rag.cache.embedding-dim}）——任一处漂移都静默降级：真库实证
 * 「向 1024 维索引写异维向量不报错、不落日志、事务成功，但该节点对向量索引永久
 * 不可见」。故启动期先做<b>零成本交叉校验</b>（只读配置，不触达供应商）：图常量与
 * 当前向量库后端维度不符即 ERROR 显形；既有索引的维度读回比对由
 * {@link GraphGateway#ensureSchema()} 内自省承担。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphSchemaInitializer {

    private final GraphGateway graphGateway;
    private final ObjectProvider<KbVectorStoreProperties> vectorStorePropertiesProvider;

    public GraphSchemaInitializer(GraphGateway graphGateway,
                                  ObjectProvider<KbVectorStoreProperties> vectorStorePropertiesProvider) {
        this.graphGateway = graphGateway;
        this.vectorStorePropertiesProvider = vectorStorePropertiesProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        verifyVectorDimensionAlignment();
        try {
            graphGateway.verifyConnectivity();
            graphGateway.ensureSchema();
        } catch (Exception e) {
            log.error("图谱 Schema 初始化失败——应用继续启动，图路检索将降级为空路；"
                + "请核验 Neo4j 连接（spring.neo4j.*）与实例状态: {}", e.getMessage());
        }
    }

    /**
     * 图常量 × 向量库配置维度交叉校验（v3.00）：不符即 ERROR 显形（不抛异常——
     * 图是检索增强件，主链不应因图谱配置漂移而拒绝启动；正确性由图路读/写守卫
     * fail-closed 兜底）。
     */
    private void verifyVectorDimensionAlignment() {
        KbVectorStoreProperties properties = vectorStorePropertiesProvider.getIfAvailable();
        if (properties == null) {
            return;   // 向量库配置缺位（异常形态）——由向量库装配侧自行报错
        }
        int vectorDimensions = properties.getProvider() == VectorStoreProvider.MILVUS
            ? properties.getMilvus().getEmbeddingDimension()
            : properties.getPgvector().getDimensions();
        int graphDimensions = GraphGateway.ENTITY_EMBEDDING_DIMENSIONS;
        if (vectorDimensions != graphDimensions) {
            log.error("图谱向量维度与向量库配置不符：图常量 {} 维 / kb.vector-store（{}）{} 维——"
                + "异维实体写入对图向量索引永久不可见（实测语义，无报错无日志）；"
                + "处置 = 对齐两处维度后重建图向量索引并回填存量实体",
                graphDimensions, properties.getProvider(), vectorDimensions);
        }
    }
}
