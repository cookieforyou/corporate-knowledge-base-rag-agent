package com.enterprise.kb.ai.guardrail;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 词表热重载协调器测试（安全簇⑥ F1）—— pub/sub 信号触发 / 成败计数 /
 * 订阅 fail-open / file: 源 mtime 轮询 / classpath 源跳过轮询。
 */
class GuardrailReloadCoordinatorTest {

    @TempDir
    Path tempDir;

    private SimpleMeterRegistry meterRegistry;
    private AiBusinessMetrics metrics;
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RTopic topic = mock(RTopic.class);
    private final GuardrailRulesRegistry registry = mock(GuardrailRulesRegistry.class);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AiBusinessMetrics(meterRegistry);
        when(registry.injectionLocation()).thenReturn("");
        when(registry.outputLocation()).thenReturn("");
    }

    private GuardrailReloadCoordinator coordinator() {
        return new GuardrailReloadCoordinator(registry, metrics, redisson, 60);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<MessageListener<String>> stubTopicSubscription() {
        when(redisson.getTopic(eq(GuardrailReloadCoordinator.RELOAD_CHANNEL), any()))
            .thenReturn(topic);
        ArgumentCaptor<MessageListener<String>> captor = ArgumentCaptor.forClass(MessageListener.class);
        when(topic.addListener(eq(String.class), captor.capture())).thenReturn(1);
        return captor;
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pubsubSignalTriggersReloadAndCountsSuccess() {
        ArgumentCaptor<MessageListener<String>> captor = stubTopicSubscription();
        when(registry.reload()).thenReturn(true);
        GuardrailReloadCoordinator coordinator = coordinator();

        coordinator.start();
        captor.getValue().onMessage(GuardrailReloadCoordinator.RELOAD_CHANNEL, "reload");

        verify(registry).reload();
        assertThat(counter("rag.guardrail.reload.succeeded")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.reload.failed")).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reloadFailureCountsFailedMetric() {
        ArgumentCaptor<MessageListener<String>> captor = stubTopicSubscription();
        when(registry.reload()).thenReturn(false);
        GuardrailReloadCoordinator coordinator = coordinator();

        coordinator.start();
        captor.getValue().onMessage(GuardrailReloadCoordinator.RELOAD_CHANNEL, "reload");

        assertThat(counter("rag.guardrail.reload.failed")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.reload.succeeded")).isZero();
    }

    @Test
    void subscriptionFailureFailsOpenWithoutBreakingStartup() {
        when(redisson.getTopic(anyString(), any())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> coordinator().start()).doesNotThrowAnyException();
    }

    @Test
    void mtimeChangeOnFileSourceTriggersReloadOnPoll() throws IOException {
        Path rulesFile = tempDir.resolve("rules.yml");
        Files.writeString(rulesFile, "rules: []\n");
        when(registry.injectionLocation()).thenReturn("file:" + rulesFile.toAbsolutePath());
        stubTopicSubscription();
        when(registry.reload()).thenReturn(true);
        GuardrailReloadCoordinator coordinator = coordinator();
        coordinator.start();

        // 强制 mtime 前移（部分文件系统秒级粒度，写入未必改变 lastModified）
        Files.setLastModifiedTime(rulesFile,
            FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        coordinator.pollOnce();

        verify(registry).reload();
        assertThat(counter("rag.guardrail.reload.succeeded")).isEqualTo(1.0);
    }

    @Test
    void unchangedFileSourceDoesNotTriggerReload() throws IOException {
        Path rulesFile = tempDir.resolve("rules.yml");
        Files.writeString(rulesFile, "rules: []\n");
        when(registry.injectionLocation()).thenReturn("file:" + rulesFile.toAbsolutePath());
        stubTopicSubscription();
        GuardrailReloadCoordinator coordinator = coordinator();
        coordinator.start();

        coordinator.pollOnce();

        verify(registry, never()).reload();
    }

    @Test
    void classpathSourceSkipsPolling() {
        stubTopicSubscription();
        GuardrailReloadCoordinator coordinator = coordinator();
        coordinator.start();

        coordinator.pollOnce();

        verify(registry, never()).reload();
    }
}
