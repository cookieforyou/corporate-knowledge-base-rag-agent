package com.enterprise.kb.ai.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索执行器上下文传递契约测试（Phase 4 簇②，簇① trace 碎片化留档修复的防回归钉）：
 * 提交线程的当前观测必须经虚拟线程边界传递至任务线程——否则检索任务内
 * embedding/rerank 观测寻父落空成独立 trace 根（簇① 留档缺陷形态）。
 */
class RetrievalExecutorContextPropagationTest {

    @Test
    void retrievalExecutorPropagatesCurrentObservation() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        AsyncTaskExecutor executor = RetrievalConfig.contextPropagatingRetrievalExecutor();

        Observation parent = Observation.createNotStarted("parent", registry).start();
        try (Observation.Scope ignored = parent.openScope()) {
            CompletableFuture<Observation> future = CompletableFuture.supplyAsync(
                registry::getCurrentObservation, executor);
            assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(parent);
        } finally {
            parent.stop();
        }
    }

    @Test
    void hybridExecutorPropagatesCurrentObservation() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        ExecutorService executor = RetrievalConfig.contextPropagatingHybridExecutor();
        try {
            Observation parent = Observation.createNotStarted("parent", registry).start();
            try (Observation.Scope ignored = parent.openScope()) {
                Observation observed = executor.submit(registry::getCurrentObservation)
                    .get(5, TimeUnit.SECONDS);
                assertThat(observed).isSameAs(parent);
            } finally {
                parent.stop();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void retrievalExecutorWithoutObservationUnchanged() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        AsyncTaskExecutor executor = RetrievalConfig.contextPropagatingRetrievalExecutor();

        // 无当前观测入口（kb-eval / 检索调试台）：任务线程同样无观测，行为不变
        CompletableFuture<Observation> future = CompletableFuture.supplyAsync(
            registry::getCurrentObservation, executor);
        assertThat(future.get(5, TimeUnit.SECONDS)).isNull();
    }
}
