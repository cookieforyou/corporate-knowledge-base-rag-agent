package com.enterprise.kb.ai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.redis.RedisChatMemoryRepository;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.chat.memory.redis.autoconfigure.RedisChatMemoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆装配回归测试（3.1 E2E 缺陷防回归）
 *
 * <p>复刻生产拓扑：用户定义 ChatMemory Bean（AgentChatClientConfig 形态）与
 * spring-ai 记忆自动配置共存。缺陷形态（2026-08-05 E2E 实锤）：
 * RedisChatMemoryAutoConfiguration#redisChatMemory 的 @ConditionalOnMissingBean
 * 检查 {RedisChatMemoryRepository, ChatMemory, ChatMemoryRepository}，用户
 * ChatMemory Bean 先注册导致 Redis 仓储静默让位、回退 InMemory——多轮对话
 * 表面连贯（进程内记忆）但 Redis 零痕迹、重启失忆。
 *
 * <p>本测试以 initialize-schema: false 保证离线可跑（仓储构造期不触网）。
 */
class ChatMemoryRedisWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            RedisChatMemoryAutoConfiguration.class,
            ChatMemoryAutoConfiguration.class))
        .withUserConfiguration(ChatMemoryRedisClientConfig.class, AgentMemoryTestConfig.class)
        .withPropertyValues(
            "spring.ai.chat.memory.redis.initialize-schema=false",
            "spring.ai.chat.memory.redis.index-name=kb-chat-memory-idx",
            "spring.ai.chat.memory.redis.key-prefix=kb:chat-memory:");

    @Test
    void redisRepositoryWinsOverInMemoryFallbackWhenUserChatMemoryBeanPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatMemoryRepository.class);
            assertThat(context.getBean(ChatMemoryRepository.class))
                .isInstanceOf(RedisChatMemoryRepository.class);
            assertThat(context).doesNotHaveBean(InMemoryChatMemoryRepository.class);
            assertThat(context).hasSingleBean(ChatMemory.class);
        });
    }

    /** 与 AgentChatClientConfig 同构的用户侧记忆装配（测试内复刻，避免拉起检索链路 Bean） */
    @Configuration
    static class AgentMemoryTestConfig {

        @Bean
        ChatMemory agentChatMemory(ChatMemoryRepository chatMemoryRepository) {
            return new FaultTolerantChatMemory(
                MessageWindowChatMemory.builder()
                    .chatMemoryRepository(chatMemoryRepository)
                    .maxMessages(20)
                    .build());
        }
    }
}
