package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GraphBackfillView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisGraphBackfillStore 单测（簇④ 批3）——Redisson mock + HashMap 承接态：
 * 受理守卫（单租户单任务）/ 状态流转 / 视图映射。
 */
class RedisGraphBackfillStoreTest {

    private static final String TENANT = "tenant-a";

    private RedisGraphBackfillStore store;
    private Map<String, String> state;
    private long succeededCount;
    private long failedCount;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        RedissonClient redisson = mock(RedissonClient.class);
        state = new HashMap<>();
        succeededCount = 0;
        failedCount = 0;

        RMap<String, String> rMap = mock(RMap.class);
        when(rMap.get(any())).thenAnswer(inv -> state.get(inv.getArgument(0)));
        when(rMap.put(any(), any())).thenAnswer(inv ->
            state.put(inv.getArgument(0), inv.getArgument(1)));
        // putAll 为 void 形态：doAnswer 桩（when(...).thenAnswer 不适用 void）
        org.mockito.Mockito.doAnswer(inv -> {
            state.putAll(inv.getArgument(0));
            return null;
        }).when(rMap).putAll(any());
        // getMap(String, Codec) 与 (String, MapOptions) 重载歧义 + 泛型推断：
        // 显式 Codec 类匹配器 + doReturn 形态钉死
        org.mockito.Mockito.doReturn(rMap).when(redisson)
            .getMap(anyString(), any(org.redisson.client.codec.Codec.class));

        RAtomicLong succeeded = mock(RAtomicLong.class);
        when(succeeded.incrementAndGet()).thenAnswer(inv -> ++succeededCount);
        when(succeeded.get()).thenAnswer(inv -> succeededCount);
        RAtomicLong failed = mock(RAtomicLong.class);
        when(failed.incrementAndGet()).thenAnswer(inv -> ++failedCount);
        when(failed.get()).thenAnswer(inv -> failedCount);
        when(redisson.getAtomicLong(ArgumentMatchers.contains("succeeded"))).thenReturn(succeeded);
        when(redisson.getAtomicLong(ArgumentMatchers.contains("failed"))).thenReturn(failed);

        store = new RedisGraphBackfillStore(redisson, 24, 6);
    }

    @Test
    void tryStartRegistersRunningAndSecondAttemptRejected() {
        assertThat(store.tryStart(TENANT, 12)).isTrue();
        assertThat(state.get("status")).isEqualTo(GraphBackfillView.STATUS_RUNNING);
        assertThat(state.get("total")).isEqualTo("12");

        assertThat(store.tryStart(TENANT, 5))
            .as("单租户单任务：在途回填拒绝并发受理（调用方转 409）")
            .isFalse();
    }

    @Test
    void recordAndFinishFlowToCompletedView() {
        store.tryStart(TENANT, 3);
        store.recordResult(TENANT, true);
        store.recordResult(TENANT, true);
        store.recordResult(TENANT, false);
        store.finish(TENANT);

        Optional<GraphBackfillView> view = store.view(TENANT);
        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo(GraphBackfillView.STATUS_COMPLETED);
        assertThat(view.get().total()).isEqualTo(3);
        assertThat(view.get().succeeded()).isEqualTo(2);
        assertThat(view.get().failed()).isEqualTo(1);
        assertThat(view.get().finishedAt()).isNotNull();
    }

    @Test
    void viewEmptyWithoutTaskState() {
        assertThat(store.view(TENANT)).isEmpty();
    }

    @Test
    void staleRunningTaskTakenOverAfterThreshold() {
        assertThat(store.tryStart(TENANT, 12)).isTrue();
        // 崩溃残留模拟：startedAt 回拨超陈旧阈值（进程崩溃后 409 死锁实证，v2.78）
        state.put("startedAt", LocalDateTime.now().minusHours(7).toString());

        assertThat(store.tryStart(TENANT, 5))
            .as("陈旧 RUNNING = 崩溃残留，就地接管重置（无需手工清 Redis 键）")
            .isTrue();
        assertThat(state.get("total")).isEqualTo("5");
        assertThat(state.get("status")).isEqualTo(GraphBackfillView.STATUS_RUNNING);
    }

    @Test
    void unparseableStartedAtTreatedAsStaleAndTakenOver() {
        state.put("status", GraphBackfillView.STATUS_RUNNING);
        state.put("startedAt", "不可解析的时间戳");

        assertThat(store.tryStart(TENANT, 2))
            .as("startedAt 缺失/不可解析同判陈旧，防 409 永久死锁")
            .isTrue();
    }
}
