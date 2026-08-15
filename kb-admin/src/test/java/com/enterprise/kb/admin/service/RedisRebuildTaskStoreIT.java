package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisRebuildTaskStore 真 Redis 集成测试（v2.36）——任务表 Redis 形态回归锚点：
 * 原子计数并发安全、明细保序、租户隔离、FIFO 淘汰、TTL 接线。
 *
 * <p>纯 JUnit 手工建连形态（无 Spring 上下文）——kb-admin IT 宿主自含，
 * 不经 kb-eval TestEvalApplication 组件扫描（避免运维 Bean ReindexGateway
 * 实现缺位击穿共享 IT 上下文）。Redis 容器形态与 kb-eval AbstractAdvisorChainIT
 * 同源（redis-stack-server + REDIS_ARGS entrypoint 注密码 + 非空密码纪律，
 * 簇⑥ D3 实证坑）。
 */
class RedisRebuildTaskStoreIT {

    static final String REDIS_PASSWORD = "kb_it_pw";

    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis/redis-stack-server:7.4.0-v0"))
        .withEnv("REDIS_ARGS", "--requirepass " + REDIS_PASSWORD)
        .withExposedPorts(6379)
        .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static RedissonClient redisson;
    static RedisRebuildTaskStore store;

    @BeforeAll
    static void boot() {
        REDIS.start();
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
            .setPassword(REDIS_PASSWORD);
        redisson = Redisson.create(config);
        store = new RedisRebuildTaskStore(redisson, new JsonMapper(), 24);
    }

    @AfterAll
    static void shutdown() {
        if (redisson != null) {
            redisson.shutdown();
        }
        REDIS.stop();
    }

    @BeforeEach
    void cleanRedis() {
        redisson.getKeys().deleteByPattern("rag:rebuild-*");
    }

    @Test
    void createAndFindRoundTripWithInitialSkipped() {
        store.create("t-1", "task-1", 3,
            List.of(new FailureView("d-x", "文档处理中，不可重入库（PARSING）")));

        Optional<RebuildTaskView> view = store.find("task-1", "t-1");

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo("RUNNING");
        assertThat(view.get().total()).isEqualTo(3);
        assertThat(view.get().succeeded()).isZero();
        assertThat(view.get().skipped()).isEqualTo(1);
        assertThat(view.get().startedAt()).isNotNull();
        assertThat(view.get().finishedAt()).isNull();
        assertThat(view.get().failures()).extracting(FailureView::docId).containsExactly("d-x");
    }

    /** 窗口并发 whenComplete 多线程回写——RAtomicLong 无锁原子性回归 */
    @Test
    void countersStayAccurateUnderConcurrentWrites() throws Exception {
        store.create("t-1", "task-c", 2000, List.of());

        int threads = 8;
        int perThread = 250;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    for (int j = 0; j < perThread; j++) {
                        store.recordSuccess("task-c");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(store.find("task-c", "t-1").orElseThrow().succeeded()).isEqualTo(threads * perThread);
    }

    @Test
    void failuresKeepInsertionOrderAndFinishMarksCompleted() {
        store.create("t-1", "task-2", 3, List.of());
        store.recordFailure("task-2", "d-a", "ETL 重入库失败（见文档 error_message）");
        store.recordSkipped("task-2", "d-b", "文档不存在或无权访问");
        store.recordFailure("task-2", "d-c", "重入库异常: timeout");
        store.finish("task-2");

        RebuildTaskView view = store.find("task-2", "t-1").orElseThrow();

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.finishedAt()).isNotNull();
        assertThat(view.failed()).isEqualTo(2);
        assertThat(view.skipped()).isEqualTo(1);
        assertThat(view.failures()).extracting(FailureView::docId)
            .containsExactly("d-a", "d-b", "d-c");
    }

    @Test
    void tenantIsolationOnFindAndList() {
        store.create("t-a", "task-a1", 1, List.of());
        store.create("t-a", "task-a2", 1, List.of());
        store.create("t-b", "task-b1", 1, List.of());

        // 跨租户 find 与不存在同语义（不泄露存在性）
        assertThat(store.find("task-a1", "t-b")).isEmpty();
        assertThat(store.listByTenant("t-b")).extracting(RebuildTaskView::taskId)
            .containsExactly("task-b1");
        assertThat(store.listByTenant("t-a")).extracting(RebuildTaskView::taskId)
            .containsExactly("task-a1", "task-a2");
    }

    @Test
    void fifoEvictionBeyondCapRemovesOldestTask() {
        List<String> taskIds = new ArrayList<>();
        for (int i = 0; i <= RedisRebuildTaskStore.MAX_TASKS_PER_TENANT; i++) {
            String taskId = "task-" + i;
            store.create("t-1", taskId, 1, List.of());
            taskIds.add(taskId);
        }

        List<RebuildTaskView> views = store.listByTenant("t-1");
        assertThat(views).hasSize(RedisRebuildTaskStore.MAX_TASKS_PER_TENANT);
        // 最老任务被淘汰且键已清（find 与不存在同语义）
        assertThat(store.find(taskIds.get(0), "t-1")).isEmpty();
        assertThat(views).extracting(RebuildTaskView::taskId).doesNotContain(taskIds.get(0));
        assertThat(store.find(taskIds.get(taskIds.size() - 1), "t-1")).isPresent();
    }

    @Test
    void ttlAppliedToTaskKeys() {
        store.create("t-1", "task-ttl", 1, List.of());

        assertThat(redisson.getMap("rag:rebuild-task:task-ttl").remainTimeToLive()).isPositive();
        assertThat(redisson.getAtomicLong("rag:rebuild-task:task-ttl:cnt:succeeded")
            .remainTimeToLive()).isPositive();
        assertThat(redisson.getDeque("rag:rebuild-tasks:t-1").remainTimeToLive()).isPositive();
    }

    @Test
    void staleIndexEntriesCleanedOnList() {
        store.create("t-1", "alive", 1, List.of());
        // 模拟 TTL 过期残留：索引有条目而任务键已失效
        redisson.getDeque("rag:rebuild-tasks:t-1").addLast("ghost-" + UUID.randomUUID());

        List<RebuildTaskView> views = store.listByTenant("t-1");

        assertThat(views).extracting(RebuildTaskView::taskId).containsExactly("alive");
        assertThat(redisson.getDeque("rag:rebuild-tasks:t-1").readAll())
            .containsExactly("alive");
    }
}
