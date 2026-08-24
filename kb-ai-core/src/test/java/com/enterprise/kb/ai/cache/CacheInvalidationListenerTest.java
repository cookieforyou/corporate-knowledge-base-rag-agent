package com.enterprise.kb.ai.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存失效事件订阅器测试（簇③ 5.6 批2）：生命周期挂/退订 + 消息 → 按文档失效
 * 委派 + 畸形消息/失效故障不击穿订阅线程。
 */
class CacheInvalidationListenerTest {

    private RedissonClient redisson;
    private RTopic topic;
    private SemanticCacheService cacheService;
    private CacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        topic = mock(RTopic.class);
        cacheService = mock(SemanticCacheService.class);
        when(redisson.getTopic(eq(CacheInvalidationListener.INVALIDATION_CHANNEL), eq(StringCodec.INSTANCE)))
            .thenReturn(topic);
        listener = new CacheInvalidationListener(redisson, cacheService, new JsonMapper());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private MessageListener<String> startAndCaptureListener() {
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenReturn(7);
        listener.start();
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass((Class) MessageListener.class);
        verify(topic).addListener(eq(String.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void startSubscribesOnDedicatedChannel() {
        listener.start();

        verify(redisson).getTopic(eq(CacheInvalidationListener.INVALIDATION_CHANNEL), eq(StringCodec.INSTANCE));
        verify(topic).addListener(eq(String.class), any(MessageListener.class));
    }

    @Test
    void validMessageDelegatesToPerDocumentInvalidation() {
        MessageListener<String> messageListener = startAndCaptureListener();

        messageListener.onMessage(CacheInvalidationListener.INVALIDATION_CHANNEL,
            "{\"tenantId\":\"t-1\",\"documentId\":\"doc-9\"}");

        verify(cacheService).invalidateByDocument("t-1", "doc-9");
    }

    @Test
    void malformedMessageDoesNotReachService() {
        MessageListener<String> messageListener = startAndCaptureListener();

        assertThatCode(() -> messageListener.onMessage(
            CacheInvalidationListener.INVALIDATION_CHANNEL, "不是合法 JSON"))
            .doesNotThrowAnyException();
        verify(cacheService, never()).invalidateByDocument(any(), any());
    }

    @Test
    void invalidationFailureDoesNotPropagateToSubscriberThread() {
        when(cacheService.invalidateByDocument(any(), any())).thenThrow(new RuntimeException("Redis 故障"));
        MessageListener<String> messageListener = startAndCaptureListener();

        assertThatCode(() -> messageListener.onMessage(
            CacheInvalidationListener.INVALIDATION_CHANNEL,
            "{\"tenantId\":\"t-1\",\"documentId\":\"doc-9\"}"))
            .doesNotThrowAnyException();
    }

    @Test
    void stopRemovesListener() {
        when(topic.addListener(eq(String.class), any(MessageListener.class))).thenReturn(7);
        listener.start();

        listener.stop();

        verify(topic).removeListener(7);
    }

    @Test
    void subscribeFailureFailsOpenAndStopStaysSafe() {
        when(redisson.getTopic(eq(CacheInvalidationListener.INVALIDATION_CHANNEL), eq(StringCodec.INSTANCE)))
            .thenThrow(new RuntimeException("Redis 不可达"));

        assertThatCode(() -> {
            listener.start();
            listener.stop();
        }).doesNotThrowAnyException();
    }
}
