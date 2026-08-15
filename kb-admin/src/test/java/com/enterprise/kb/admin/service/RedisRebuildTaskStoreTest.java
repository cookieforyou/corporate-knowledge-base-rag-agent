package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RDeque;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisRebuildTaskStore 单测（v2.36）——Redisson 经 mock 隔离：
 * fail-closed/fail-open 语义、跨租户隐藏、FIFO 淘汰、过期残留清理、键布局。
 * 真实 Redis 回归（原子计数并发/明细保序/TTL）见 kb-eval RedisRebuildTaskStoreIT。
 */
class RedisRebuildTaskStoreTest {

    private RedissonClient redisson;
    private RMap<String, String> stateMap;
    private RList<String> failuresList;
    private RDeque<String> indexDeque;
    private RKeys keys;
    private final Map<String, RAtomicLong> counters = new HashMap<>();
    private RedisRebuildTaskStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisson = mock(RedissonClient.class);
        stateMap = mock(RMap.class);
        failuresList = mock(RList.class);
        indexDeque = mock(RDeque.class);
        keys = mock(RKeys.class);
        counters.clear();

        // doReturn 形态规避泛型推断歧义（getMap/getList/getDeque 均带 Options 重载）
        doReturn(stateMap).when(redisson).getMap(anyString(), any(Codec.class));
        doReturn(failuresList).when(redisson).getList(anyString(), any(Codec.class));
        doReturn(indexDeque).when(redisson).getDeque(anyString(), any(Codec.class));
        when(redisson.getKeys()).thenReturn(keys);
        when(redisson.getAtomicLong(anyString())).thenAnswer(inv ->
            counters.computeIfAbsent(inv.getArgument(0), k -> mock(RAtomicLong.class)));

        store = new RedisRebuildTaskStore(redisson, new JsonMapper(), 24);
    }

    @Test
    void createRegistersStateCountersAndTenantIndex() {
        store.create("t-1", "task-1", 3, List.of(new FailureView("d-x", "处理中")));

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(stateMap).putAll(fields.capture());
        assertThat(fields.getValue())
            .containsEntry("tenantId", "t-1")
            .containsEntry("status", "RUNNING")
            .containsEntry("total", "3");
        assertThat(fields.getValue()).containsKey("startedAt");
        verify(stateMap).expire(any(Duration.class));
        verify(failuresList).expire(any(Duration.class));
        verify(indexDeque).addLast("task-1");
        verify(indexDeque).expire(any(Duration.class));
        assertThat(counters.keySet()).anyMatch(k -> k.endsWith(":cnt:skipped"));
        // 前置 skipped 明细落 failures 列表 + skipped 计数
        verify(failuresList).addAll(any());
        verify(counters.get(counterKey("task-1", "skipped"))).addAndGet(1);
    }

    @Test
    void createRedisFailureThrowsStoreUnavailable() {
        doThrow(new RuntimeException("connection refused"))
            .when(redisson).getMap(anyString(), any(Codec.class));

        assertThatThrownBy(() -> store.create("t-1", "task-1", 1, List.of()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("REBUILD_STORE_UNAVAILABLE");
    }

    @Test
    void recordOperationsSwallowRedisFailures() {
        when(redisson.getAtomicLong(anyString())).thenThrow(new RuntimeException("connection reset"));

        assertThatCode(() -> {
            store.recordSuccess("task-1");
            store.recordFailure("task-1", "d-1", "boom");
            store.recordSkipped("task-1", "d-2", "busy");
            store.finish("task-1");
        }).doesNotThrowAnyException();
    }

    @Test
    void findHidesMissingAndCrossTenantTasks() {
        when(stateMap.readAllMap()).thenReturn(Map.of());
        assertThat(store.find("task-1", "t-1")).isEmpty();

        when(stateMap.readAllMap()).thenReturn(Map.of("tenantId", "t-other", "status", "RUNNING"));
        assertThat(store.find("task-1", "t-1")).isEmpty();
    }

    @Test
    void findAssemblesViewFromHashCountersAndFailures() {
        when(stateMap.readAllMap()).thenReturn(Map.of(
            "tenantId", "t-1", "status", "COMPLETED", "total", "5",
            "startedAt", "2026-08-15T10:00:00", "finishedAt", "2026-08-15T10:05:00"));
        RAtomicLong succeeded = mock(RAtomicLong.class);
        when(succeeded.get()).thenReturn(3L);
        counters.put(counterKey("task-1", "succeeded"), succeeded);
        RAtomicLong failed = mock(RAtomicLong.class);
        when(failed.get()).thenReturn(1L);
        counters.put(counterKey("task-1", "failed"), failed);
        RAtomicLong skipped = mock(RAtomicLong.class);
        when(skipped.get()).thenReturn(1L);
        counters.put(counterKey("task-1", "skipped"), skipped);
        when(failuresList.readAll()).thenReturn(List.of(
            "{\"docId\":\"d-1\",\"reason\":\"重入库异常\"}",
            "corrupt-json{"));

        Optional<RebuildTaskView> view = store.find("task-1", "t-1");

        assertThat(view).isPresent();
        assertThat(view.get().status()).isEqualTo("COMPLETED");
        assertThat(view.get().total()).isEqualTo(5);
        assertThat(view.get().succeeded()).isEqualTo(3);
        assertThat(view.get().failed()).isEqualTo(1);
        assertThat(view.get().skipped()).isEqualTo(1);
        assertThat(view.get().finishedAt()).isNotNull();
        // 损坏明细跳过不击穿视图
        assertThat(view.get().failures()).hasSize(1);
        assertThat(view.get().failures().get(0).docId()).isEqualTo("d-1");
    }

    @Test
    void findRedisFailureThrowsStoreUnavailable() {
        when(stateMap.readAllMap()).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> store.find("task-1", "t-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("REBUILD_STORE_UNAVAILABLE");
    }

    @Test
    void listByTenantCleansStaleIndexEntries() {
        when(indexDeque.readAll()).thenReturn(List.of("alive", "expired"));
        // alive：hash 有值；expired：hash 空（TTL 过期残留）
        RMap<String, String> aliveMap = mapWithTenant("t-1");
        doReturn(aliveMap).when(redisson).getMap(eq("rag:rebuild-task:alive"), any(Codec.class));
        doReturn(mock(RMap.class)).when(redisson).getMap(eq("rag:rebuild-task:expired"), any(Codec.class));
        doReturn(mock(RList.class)).when(redisson).getList(eq("rag:rebuild-task:alive:failures"), any(Codec.class));
        doReturn(mock(RList.class)).when(redisson).getList(eq("rag:rebuild-task:expired:failures"), any(Codec.class));

        List<RebuildTaskView> views = store.listByTenant("t-1");

        assertThat(views).extracting(RebuildTaskView::taskId).containsExactly("alive");
        verify(indexDeque).removeAll(List.of("expired"));
    }

    @Test
    void createEvictsOldestBeyondCap() {
        when(indexDeque.size()).thenReturn(RedisRebuildTaskStore.MAX_TASKS_PER_TENANT + 1, 0);
        when(indexDeque.pollFirst()).thenReturn("task-0", null);

        store.create("t-1", "task-21", 1, List.of());

        verify(keys).deleteByPattern("rag:rebuild-task:task-0*");
        verify(indexDeque, atLeastOnce()).size();
    }

    /** 淘汰键清理失败不击穿 create（残留键自带 TTL 兜底，尽力而为语义） */
    @Test
    void evictionKeyCleanupFailureDoesNotBreakCreate() {
        when(indexDeque.size()).thenReturn(RedisRebuildTaskStore.MAX_TASKS_PER_TENANT + 1, 0);
        when(indexDeque.pollFirst()).thenReturn("task-0", null);
        when(redisson.getKeys()).thenThrow(new RuntimeException("scan failed"));

        assertThatCode(() -> store.create("t-1", "task-21", 1, List.of()))
            .doesNotThrowAnyException();
        verify(keys, never()).deleteByPattern(anyString());
    }

    // ── helpers ──

    private static String counterKey(String taskId, String name) {
        return "rag:rebuild-task:" + taskId + ":cnt:" + name;
    }

    @SuppressWarnings("unchecked")
    private static RMap<String, String> mapWithTenant(String tenantId) {
        RMap<String, String> map = mock(RMap.class);
        when(map.readAllMap()).thenReturn(Map.of(
            "tenantId", tenantId, "status", "RUNNING", "total", "1"));
        return map;
    }
}
