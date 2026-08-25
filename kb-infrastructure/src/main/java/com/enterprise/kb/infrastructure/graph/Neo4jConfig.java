package com.enterprise.kb.infrastructure.graph;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j 驱动条件装配（Phase 5 簇④ GraphRAG）。
 *
 * <p>{@code rag.graph.enabled=true} 时才创建 {@link Driver} Bean（destroyMethod=close）——
 * 关闭态整个图谱域 Bean 缺位，消费侧经 {@code ObjectProvider} 容忍（同语义缓存簇③先例）。
 *
 * <p>手工装配而非 {@code spring-boot-starter-neo4j} 自动配置：避免关闭态残留
 * Driver/Health 副作用，连接参数完全由 {@link Neo4jProperties} 钉死。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(Neo4jProperties.class)
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(Neo4jProperties properties) {
        Config config = Config.builder()
            .withConnectionTimeout(properties.getConnectionTimeoutSeconds(), TimeUnit.SECONDS)
            .withMaxConnectionPoolSize(10)
            .build();
        Driver driver = GraphDatabase.driver(
            properties.getUri(),
            AuthTokens.basic(properties.getAuthentication().getUsername(),
                properties.getAuthentication().getPassword()),
            config);
        log.info("Neo4j Driver 已装配 → uri={}, database={}", properties.getUri(), properties.getDatabase());
        return driver;
    }

    @Bean
    public GraphGateway graphGateway(Driver neo4jDriver, Neo4jProperties properties) {
        return new Neo4jGraphGateway(neo4jDriver, properties);
    }
}
