package com.enterprise.kb.ai.memory;

import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.model.chat.memory.repository.redis.autoconfigure.RedisChatMemoryRepositoryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.RedisClient;

import java.time.Duration;

/**
 * 会话记忆 Redis 侧装配（3.1：Redis 记忆仓储显式装配）
 *
 * <p><b>jedisClient 已退役为框架自动装配（2026-09-03，Spring AI 2.0.1）</b>：
 * 2.0.1 自动配置 jedisClient 补齐 username/password；database 虽无字段，
 * Jedis Builder 缺省即 0（字节码级核验 iconst_0）且自动装配两条分支均不
 * 显式设置——RediSearch db0 硬约束天然满足，手工覆盖失去必要性。
 * 连接信息单一来源纪律不变：application-ai.yml 经占位符桥接
 * {@code spring.data.redis.*}（host/port/password），与 Redisson 共消费同源。
 *
 * <p><b>redisChatMemoryRepository 显式装配必须保留（2026-08-05 E2E 缺陷修复，坑位⑦主体）</b>：
 * RedisChatMemoryRepositoryAutoConfiguration#redisChatMemoryRepository 的
 * @ConditionalOnMissingBean 同时检查 {RedisChatMemoryRepository, <b>ChatMemory</b>, ChatMemoryRepository}
 * 三类型——RagAgentChatClientConfig 的用户定义 agentChatMemory（ChatMemory 型）
 * 先于自动配置注册，条件命中 → Redis 仓储 Bean 静默让位 →
 * ChatMemoryAutoConfiguration 回退 <b>InMemoryChatMemoryRepository</b>（2.0.1 该机制未变）。
 * 症状极具迷惑性：多轮对话表面连贯（进程内记忆在工作），Redis 却无索引无键、
 * 重启即失忆，且无任何报错。用户定义 Bean 不参与条件让位，显式装配根治；
 * 本 Bean 存在后自动配置的 redisChatMemoryRepository 与 InMemory 回退双双让位。
 * 回归测试见 ChatMemoryRedisWiringTest。
 */
@Configuration
public class ChatMemoryRedisClientConfig {

    /**
     * Redis 记忆仓储显式装配：属性桥接与自动配置等价
     * （indexName/keyPrefix/timeToLive/initializeSchema，前缀
     * spring.ai.chat.memory.repository.redis.*——2.0.1 迁移，旧前缀仅过渡回落）。
     * initializeSchema 缺省视为 true——仓储构造期即 FT.CREATE（失败抛异常阻断启动，
     * 宁可启动期暴露也不静默降级）；kb-eval 侧以 initialize-schema: false 覆盖。
     */
    @Bean
    public RedisChatMemoryRepository redisChatMemoryRepository(RedisClient jedisClient,
                                                               RedisChatMemoryRepositoryProperties properties) {
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
