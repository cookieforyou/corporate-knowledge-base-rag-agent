package com.enterprise.kb.ai.memory;

import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

/**
 * 会话记忆 Redis 侧装配（3.1：Jedis 客户端 + Redis 记忆仓储）
 *
 * <p><b>jedisClient 覆盖</b>：spring-ai 自动配置的 jedisClient 仅支持 host/port
 * （RedisChatMemoryProperties 无 password/database），ECS Redis 带密码必连失败。
 * 自动配置为 @ConditionalOnMissingBean，本 Bean 覆盖之。
 *
 * <p><b>连接信息单一来源</b>：host/port/password/database 统一取自
 * {@code spring.data.redis.*}（REDIS_HOST / REDIS_PORT / REDIS_PASSWORD /
 * REDIS_DB 环境变量）——与 Redisson（ETL 进度双通道）共用同一组连接参数。
 * Jedis 7 客户端惰性建连，Bean 创建不触网。
 *
 * <p><b>redisChatMemoryRepository 显式装配（2026-08-05 E2E 缺陷修复）</b>：
 * RedisChatMemoryAutoConfiguration#redisChatMemory 的 @ConditionalOnMissingBean
 * 同时检查 {RedisChatMemoryRepository, <b>ChatMemory</b>, ChatMemoryRepository}
 * 三类型——RagAgentChatClientConfig 的用户定义 agentChatMemory（ChatMemory 型）
 * 先于自动配置注册，条件命中 → Redis 仓储 Bean 静默让位 →
 * ChatMemoryAutoConfiguration 回退 <b>InMemoryChatMemoryRepository</b>。
 * 症状极具迷惑性：多轮对话表面连贯（进程内记忆在工作），Redis 却无索引无键、
 * 重启即失忆，且无任何报错。用户定义 Bean 不参与条件让位，显式装配根治；
 * 本 Bean 存在后自动配置的 redisChatMemory 与 InMemory 回退双双让位。
 * 回归测试见 ChatMemoryRedisWiringTest。
 */
@Configuration
public class ChatMemoryRedisClientConfig {

    @Bean
    public RedisClient jedisClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {

        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
            .database(database);
        // 空密码（本地开发形态）不设 credentials；非空（ECS 生产形态）按密码认证
        if (password != null && !password.isEmpty()) {
            config.password(password);
        }
        return RedisClient.builder()
            .hostAndPort(host, port)
            .clientConfig(config.build())
            .build();
    }

    /**
     * Redis 记忆仓储显式装配：属性桥接与自动配置等价
     * （indexName/keyPrefix/timeToLive/initializeSchema，前缀 spring.ai.chat.memory.redis.*）。
     * initializeSchema 缺省视为 true——仓储构造期即 FT.CREATE（失败抛异常阻断启动，
     * 宁可启动期暴露也不静默降级）；kb-eval 侧以 initialize-schema: false 覆盖。
     */
    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository(RedisClient jedisClient,
                                                               RedisChatMemoryProperties properties) {
        RedisChatMemoryRepository.Builder builder = RedisChatMemoryRepository.builder()
            .jedisClient(jedisClient);
        if (StringUtils.hasText(properties.getIndexName())) {
            builder.indexName(properties.getIndexName());
        }
        if (StringUtils.hasText(properties.getKeyPrefix())) {
            builder.keyPrefix(properties.getKeyPrefix());
        }
        Duration ttl = properties.getTimeToLive();
        if (ttl != null && ttl.toSeconds() > 0) {
            builder.timeToLive(ttl);
        }
        builder.initializeSchema(properties.getInitializeSchema() == null
            || properties.getInitializeSchema());
        return builder.build();
    }
}
