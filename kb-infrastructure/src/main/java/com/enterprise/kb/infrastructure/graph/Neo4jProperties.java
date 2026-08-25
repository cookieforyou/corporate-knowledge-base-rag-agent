package com.enterprise.kb.infrastructure.graph;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Neo4j 连接配置（Phase 5 簇④ GraphRAG）。
 *
 * <p>绑定 {@code spring.neo4j.*}（基建连接段落 application-infra.yml，与 PG/ES/Redis/MinIO
 * 同层）；环境变量覆盖 = {@code NEO4J_URI / NEO4J_USERNAME / NEO4J_PASSWORD}。
 *
 * <p>手工装配 {@link org.neo4j.driver.Driver}（不引 Spring Data Neo4j / spring-boot-neo4j
 * starter）——连接生命周期完全受 {@code rag.graph.enabled} 条件装配门控，
 * 关闭态无 Driver Bean、无连接尝试（链路形态零变化纪律）。
 */
@Data
@ConfigurationProperties(prefix = "spring.neo4j")
public class Neo4jProperties {

    /** Bolt 连接地址（第二台 ECS 自托管 Community 实例） */
    private String uri = "bolt://localhost:7687";

    private final Authentication authentication = new Authentication();

    /** 目标数据库名（Community 单库 = neo4j） */
    private String database = "neo4j";

    private int connectionTimeoutSeconds = 10;

    /** 会话级查询超时（秒）——图路检索/写入的单次事务上限 */
    private int queryTimeoutSeconds = 5;

    @Data
    public static class Authentication {
        private String username = "neo4j";
        private String password = "";
    }
}
