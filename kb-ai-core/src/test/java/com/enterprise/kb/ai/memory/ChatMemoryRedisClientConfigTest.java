package com.enterprise.kb.ai.memory;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.RedisClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 会话记忆 Jedis 客户端装配测试（3.1 密码适配）
 *
 * <p>Jedis 7 客户端为惰性建连：Bean 构建仅组装连接池配置、不触网，
 * 故可在无 Redis 环境断言构建成功（含密码形态与空密码形态）。
 */
class ChatMemoryRedisClientConfigTest {

    private final ChatMemoryRedisClientConfig config = new ChatMemoryRedisClientConfig();

    @Test
    void buildsClientWithPassword() {
        RedisClient client = config.jedisClient("localhost", 6379, "secret-pass", 0);

        assertThat(client).isNotNull();
    }

    @Test
    void buildsClientWithoutPasswordForLocalDev() {
        assertThatCode(() -> config.jedisClient("localhost", 6379, "", 0))
            .doesNotThrowAnyException();
    }
}
