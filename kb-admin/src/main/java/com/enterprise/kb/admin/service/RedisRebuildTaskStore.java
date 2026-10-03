package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;
import com.enterprise.kb.commons.constant.Constants;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RDeque;
import org.redisson.api.RExpirable;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 重建任务表 Redis 形态（v2.36，取代 v2.33 内存 LinkedHashMap）。
 *
 * <p><b>迁移动因</b>：内存表重启丢失、多实例互不可见，且任务列表全局共享
 * （跨租户可见失败明细 docId）。Redis 形态重启保留（TTL 窗口内）、租户域
 * 隔离、索引重建的执行实例与轮询实例解耦（执行仍在受理实例，任务表共享）。
 *
 * <p><b>键布局</b>（连接参数与 HITL 账本同源——application-infra.yml
 * {@code spring.data.redis.*} 单一来源，Redisson starter 自动装配）：
 * <ul>
 *   <li>{@code rag:rebuild-task:{taskId}}——RMap&lt;String,String&gt;：
 *       tenantId / status / total / startedAt / finishedAt</li>
 *   <li>{@code rag:rebuild-task:{taskId}:cnt:{succeeded|failed|skipped}}——
 *       RAtomicLong 原子计数（窗口并发 whenComplete 多线程回写无锁安全）</li>
 *   <li>{@code rag:rebuild-task:{taskId}:failures}——RList&lt;String&gt;
 *       明细 JSON（RPUSH 追加天然保序，与计数分离免读改写竞态）</li>
 *   <li>{@code rag:rebuild-tasks:{tenantId}}——RDeque 租户任务索引
 *       （insertion order，最新在尾，上限 {@value #MAX_TASKS_PER_TENANT}
 *       FIFO 淘汰并清被汰者全部键）</li>
 * </ul>
 * 存储形态取字符串键值（任意 Redisson codec 均安全，同 ToolApprovalService
 * 纪律，StringCodec 显式钉死免默认 codec 漂移）；TTL
 * {@code rag.admin.rebuild.task-ttl-hours}（默认 24h）挂全部任务键。
 *
 * <p><b>TTL 挂全键（v3.04 修复）</b>：计数键与明细列表键是<b>惰性创建</b>的（Redis 中
 * 空列表不存在、计数键由 INCR 建），故登记期的 {@code EXPIRE} 对它们静默失效——
 * 已改为计数键登记期 {@code set(0)} 建键后挂 TTL、明细列表首次写入后按状态键剩余寿命
 * 对齐（{@link #alignTtlWithState}）。原形态每任务泄漏 4 个无 TTL 键，仅靠 FIFO
 * 淘汰的按前缀清理兜底（kb-admin IT {@code ttlAppliedToTaskKeys} 实证）。
 *
 * <p><b>Redis 故障语义</b>：create / find / list fail-closed 抛
 * {@code REBUILD_STORE_UNAVAILABLE}（任务不可无表启动；状态不可读不谎报）；
 * 在途任务的 record* / finish 仅告警不阻断——重建主流程经 ReindexGateway
 * 已独立完成三库收敛，任务表是观测旁路（同 9.4 从属副本容错哲学）。
 * 已知降级：Redis 故障期 finish 丢失 → 任务残留 RUNNING 至 TTL 过期。
 */
@Slf4j
@Service
public class RedisRebuildTaskStore implements RebuildTaskStore {

    static final String TASK_KEY_PREFIX = "rag:rebuild-task:";
    static final String INDEX_KEY_PREFIX = "rag:rebuild-tasks:";
    static final int MAX_TASKS_PER_TENANT = 20;

    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_COMPLETED = "COMPLETED";

    private final RedissonClient redissonClient;
    private final JsonMapper jsonMapper;
    private final Duration taskTtl;

    public RedisRebuildTaskStore(RedissonClient redissonClient,
                                 JsonMapper jsonMapper,
                                 @Value("${rag.admin.rebuild.task-ttl-hours:24}") long taskTtlHours) {
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
        this.taskTtl = Duration.ofHours(Math.max(1, taskTtlHours));
    }

    @Override
    public void create(String tenantId, String taskId, int total, List<FailureView> initialSkipped) {
        try {
            RMap<String, String> state = stateMap(taskId);
            state.putAll(Map.of(
                "tenantId", tenantId,
                "status", STATUS_RUNNING,
                "total", Integer.toString(total),
                "startedAt", LocalDateTime.now().toString()));
            state.expire(taskTtl);
            for (String name : new String[]{"succeeded", "failed", "skipped"}) {
                // v3.04 修复（kb-admin IT 挖出）：EXPIRE 对**尚不存在**的键是 no-op，而计数键
                // 由 INCR 惰性创建 → 原形态「挂 TTL」静默失效，计数键永久驻留（每任务 3 个 +
                // 明细列表 1 个，仅靠 FIFO 淘汰兜底）。故先 set(0) 显式建键再挂 TTL。
                RAtomicLong cnt = counter(taskId, name);
                cnt.set(0);
                cnt.expire(taskTtl);
            }
            RList<String> failures = failuresList(taskId);
            if (!initialSkipped.isEmpty()) {
                failures.addAll(initialSkipped.stream().map(this::toJson).toList());
                counter(taskId, "skipped").addAndGet(initialSkipped.size());
                alignTtlWithState(taskId, failures);
            }
            RDeque<String> index = indexDeque(tenantId);
            index.addLast(taskId);
            index.expire(taskTtl);
            while (index.size() > MAX_TASKS_PER_TENANT) {
                String evicted = index.pollFirst();
                if (evicted == null) {
                    break;
                }
                deleteTaskKeys(evicted);
            }
        } catch (Exception e) {
            log.error("重建任务登记失败（Redis 故障），fail-closed 拒绝启动: {}", e.getMessage());
            throw storeUnavailable();
        }
    }

    @Override
    public void recordSuccess(String taskId) {
        try {
            counter(taskId, "succeeded").incrementAndGet();
        } catch (Exception e) {
            log.warn("重建任务计数写入失败（不阻断）: taskId={}, {}", taskId, e.getMessage());
        }
    }

    @Override
    public void recordFailure(String taskId, String docId, String reason) {
        try {
            counter(taskId, "failed").incrementAndGet();
            RList<String> failures = failuresList(taskId);
            failures.add(toJson(new FailureView(docId, reason)));
            alignTtlWithState(taskId, failures);   // 首次写入才建键（空列表在 Redis 中不存在）
        } catch (Exception e) {
            log.warn("重建任务失败明细写入失败（不阻断）: taskId={}, {}", taskId, e.getMessage());
        }
    }

    @Override
    public void recordSkipped(String taskId, String docId, String reason) {
        try {
            counter(taskId, "skipped").incrementAndGet();
            RList<String> failures = failuresList(taskId);
            failures.add(toJson(new FailureView(docId, reason)));
            alignTtlWithState(taskId, failures);
        } catch (Exception e) {
            log.warn("重建任务跳过明细写入失败（不阻断）: taskId={}, {}", taskId, e.getMessage());
        }
    }

    @Override
    public void finish(String taskId) {
        try {
            RMap<String, String> state = stateMap(taskId);
            state.put("status", STATUS_COMPLETED);
            state.put("finishedAt", LocalDateTime.now().toString());
        } catch (Exception e) {
            log.warn("重建任务终态写入失败（残留 RUNNING 至 TTL）: taskId={}, {}", taskId, e.getMessage());
        }
    }

    @Override
    public Optional<RebuildTaskView> find(String taskId, String requiredTenantId) {
        try {
            Map<String, String> fields = stateMap(taskId).readAllMap();
            if (fields.isEmpty() || !Objects.equals(fields.get("tenantId"), requiredTenantId)) {
                return Optional.empty();   // 不存在与跨租户一律隐藏存在性
            }
            List<FailureView> failures = failuresList(taskId).readAll().stream()
                .map(this::fromJson)
                .filter(Objects::nonNull)
                .toList();
            return Optional.of(new RebuildTaskView(taskId, fields.get("status"),
                parseInt(fields.get("total")),
                (int) counter(taskId, "succeeded").get(),
                (int) counter(taskId, "failed").get(),
                (int) counter(taskId, "skipped").get(),
                parseTime(fields.get("startedAt")),
                parseTime(fields.get("finishedAt")),
                failures));
        } catch (Exception e) {
            log.error("重建任务读取失败（Redis 故障）: taskId={}, {}", taskId, e.getMessage());
            throw storeUnavailable();
        }
    }

    @Override
    public List<RebuildTaskView> listByTenant(String tenantId) {
        try {
            RDeque<String> index = indexDeque(tenantId);
            List<RebuildTaskView> views = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            for (String taskId : index.readAll()) {
                find(taskId, tenantId).ifPresentOrElse(views::add, () -> stale.add(taskId));
            }
            if (!stale.isEmpty()) {
                index.removeAll(stale);    // TTL 过期残留惰性清理
            }
            return views;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("重建任务列表读取失败（Redis 故障）: tenantId={}, {}", tenantId, e.getMessage());
            throw storeUnavailable();
        }
    }

    // ── 内部方法 ──

    private RMap<String, String> stateMap(String taskId) {
        return redissonClient.getMap(TASK_KEY_PREFIX + taskId, StringCodec.INSTANCE);
    }

    private RAtomicLong counter(String taskId, String name) {
        return redissonClient.getAtomicLong(TASK_KEY_PREFIX + taskId + ":cnt:" + name);
    }

    private RList<String> failuresList(String taskId) {
        return redissonClient.getList(TASK_KEY_PREFIX + taskId + ":failures", StringCodec.INSTANCE);
    }

    private RDeque<String> indexDeque(String tenantId) {
        return redissonClient.getDeque(INDEX_KEY_PREFIX + tenantId, StringCodec.INSTANCE);
    }

    /** 淘汰任务的键清理（尽力而为：残留键自带 TTL 兜底） */
    private void deleteTaskKeys(String taskId) {
        try {
            redissonClient.getKeys().deleteByPattern(TASK_KEY_PREFIX + taskId + "*");
        } catch (Exception e) {
            log.warn("淘汰任务键清理失败（TTL 兜底）: taskId={}, {}", taskId, e.getMessage());
        }
    }

    /**
     * 惰性建键后的 TTL 对齐（v3.04 修复，kb-admin IT 挖出）：Redis 里空列表与未自增的
     * 计数键**并不存在**，登记期 {@code EXPIRE} 对不存在键是 no-op——首次写入后必须补挂，
     * 否则该键无 TTL 永久驻留（只有 FIFO 淘汰的按前缀清理兜底）。寿命取<b>状态键剩余
     * TTL</b>，使任务全部键同刻过期（状态键 = 任务寿命单一事实源）；状态键已过期
     * （在途写入撞上 TTL 残留）时回落一个完整 TTL，保证任何路径都不留无 TTL 的键。
     */
    private void alignTtlWithState(String taskId, RExpirable... keys) {
        long remaining = stateMap(taskId).remainTimeToLive();
        Duration ttl = remaining > 0 ? Duration.ofMillis(remaining) : taskTtl;
        for (RExpirable key : keys) {
            key.expire(ttl);
        }
    }

    private String toJson(FailureView failure) {
        return jsonMapper.writeValueAsString(failure);
    }

    private FailureView fromJson(String json) {
        try {
            return jsonMapper.readValue(json, FailureView.class);
        } catch (Exception e) {
            log.warn("重建任务明细反序列化失败（跳过该条）: {}", e.getMessage());
            return null;
        }
    }

    private static int parseInt(String value) {
        try {
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDateTime parseTime(String value) {
        try {
            return value != null ? LocalDateTime.parse(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static BusinessException storeUnavailable() {
        return new BusinessException(Constants.ErrorCodes.REBUILD_STORE_UNAVAILABLE, "重建任务服务暂不可用，请稍后再试");
    }
}
