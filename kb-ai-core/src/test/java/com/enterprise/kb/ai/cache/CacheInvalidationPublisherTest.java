package com.enterprise.kb.ai.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 缓存失效事件发布器测试（簇③ 5.6 批2）：频道/载荷契约 + 参数守卫 + fail-open。
 */
class CacheInvalidationPublisherTest {

    private RedissonClient redisson;
    private RTopic topic;
    private JsonMapper jsonMapper;
    private CacheInvalidationPublisher publisher;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        jsonMapper = new JsonMapper();
        when(redisson.getTopic(eq(CacheInvalidationListener.INVALIDATION_CHANNEL), eq(StringCodec.INSTANCE)))
            .thenReturn(topic);
        publisher = new CacheInvalidationPublisher(redisson, jsonMapper);
    }

    @Test
    void publishSendsJsonEventOnDedicatedChannel() {
        publisher.publish("t-1", "doc-9");

        ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
        verify(topic).publish(message.capture());
        CacheInvalidationEvent event = jsonMapper.readValue(String.valueOf(message.getValue()),
            CacheInvalidationEvent.class);
        assertThat(event.tenantId()).isEqualTo("t-1");
        assertThat(event.documentId()).isEqualTo("doc-9");
    }

    @Test
    void blankArgumentsSkipPublish() {
        publisher.publish(null, "doc-9");
        publisher.publish(" ", "doc-9");
        publisher.publish("t-1", null);
        publisher.publish("t-1", " ");

        verifyNoInteractions(redisson);
    }

    @Test
    void redisFailureDoesNotPropagate() {
        when(redisson.getTopic(eq(CacheInvalidationListener.INVALIDATION_CHANNEL), eq(StringCodec.INSTANCE)))
            .thenThrow(new RuntimeException("Redis 不可达"));

        assertThatCode(() -> publisher.publish("t-1", "doc-9")).doesNotThrowAnyException();
    }
}
