package com.enterprise.kb.infrastructure.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 图谱 Schema 启动初始化（Phase 5 簇④）。
 *
 * <p>{@code rag.graph.enabled=true} 时于 ApplicationReady 执行连通性校验 +
 * 幂等 DDL（约束/索引/向量索引）。<b>失败不阻断应用启动</b>（图谱是检索增强件
 * 非事实源）——错误日志显形，运行期图路按单路容错降级为空（降级矩阵 10.2 同语义），
 * 对齐语义缓存「能力探测自关」先例的容错纪律。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphSchemaInitializer {

    private final GraphGateway graphGateway;

    public GraphSchemaInitializer(GraphGateway graphGateway) {
        this.graphGateway = graphGateway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            graphGateway.verifyConnectivity();
            graphGateway.ensureSchema();
        } catch (Exception e) {
            log.error("图谱 Schema 初始化失败——应用继续启动，图路检索将降级为空路；"
                + "请核验 Neo4j 连接（spring.neo4j.*）与实例状态: {}", e.getMessage());
        }
    }
}
