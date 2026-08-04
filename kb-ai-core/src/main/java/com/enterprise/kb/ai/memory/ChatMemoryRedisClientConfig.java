package com.enterprise.kb.ai.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

/**
 * 会话记忆 Jedis 客户端覆盖装配（3.1 密码适配）
 *
 * <p>spring-ai 的 RedisChatMemoryAutoConfiguration 自带的 jedisClient 仅支持
 * host/port（无 password/database），ECS Redis 设置了访问密码，直连必失败。
 * 自动配置的 jedisClient 为 @ConditionalOnMissingBean，本 Bean 覆盖之。
 *
 * <p><b>连接信息单一来源</b>：host/port/password/database 统一取自
 * {@code spring.data.redis.*}（REDIS_HOST / REDIS_PORT / REDIS_PASSWORD /
 * REDIS_DB 环境变量）——与 Redisson（ETL 进度双通道）共用同一组连接参数，
 * 避免两处配置漂移。记忆专属配置（indexName/keyPrefix/TTL/initializeSchema）
 * 仍走 {@code spring.ai.chat.memory.redis.*}。
 *
 * <p>Jedis 7 客户端为惰性建连（构造期仅组装连接池配置），Bean 创建不触网。
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
}
