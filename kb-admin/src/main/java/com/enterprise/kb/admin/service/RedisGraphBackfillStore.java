package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.GraphBackfillView;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 图谱回填任务状态 Redis 形态（簇④ 5.1 批3）——{@code RedisRebuildTaskStore}
 * 同源纪律的轻量变体：单租户单任务（图谱回填幂等收敛，无需多任务历史），
 * 键布局 {@code rag:graph-backfill:{tenantId}}（RMap 状态 + 双原子计数），
 * StringCodec 显式钉死、TTL 挂全部任务键（默认 24h，配置同源重建任务表）。
 *
 * <p><b>Redis 故障语义</b>（同重建任务表）：受理 {@code tryStart} fail-closed
 * 上抛（任务不可无表启动）；在途 {@code record* / finish} 仅告警不阻断
 * （抽取主流程经 {@code GraphExtractionService} 独立完成，状态表是观测旁路）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class RedisGraphBackfillStore {

    static final String KEY_PREFIX = "rag:graph-backfill:";

    private final RedissonClient redissonClient;
    private final Duration taskTtl;

    public RedisGraphBackfillStore(RedissonClient redissonClient,
                                   @Value("${rag.admin.rebuild.task-ttl-hours:24}") long taskTtlHours) {
        this.redissonClient = redissonClient;
        this.taskTtl = Duration.ofHours(Math.max(1, taskTtlHours));
    }

    /**
     * 受理任务：既有 RUNNING 态 → false（调用方转 409）；否则登记
     * RUNNING + total + startedAt 并挂 TTL。
     */
    public boolean tryStart(String tenantId, int total) {
        RMap<String, String> state = stateMap(tenantId);
        if (GraphBackfillView.STATUS_RUNNING.equals(state.get("status"))) {
            return false;
        }
        state.putAll(Map.of(
            "status", GraphBackfillView.STATUS_RUNNING,
            "total", String.valueOf(total),
            "startedAt", LocalDateTime.now().toString()));
        state.expire(taskTtl);
        counter(tenantId, "succeeded").set(0);
        counter(tenantId, "failed").set(0);
        return true;
    }

    public void recordResult(String tenantId, boolean success) {
        try {
            counter(tenantId, success ? "succeeded" : "failed").incrementAndGet();
        } catch (Exception e) {
            log.warn("图谱回填计数回写失败（不阻断在途任务）: {}", e.getMessage());
        }
    }

    public void finish(String tenantId) {
        try {
            RMap<String, String> state = stateMap(tenantId);
            state.put("status", GraphBackfillView.STATUS_COMPLETED);
            state.put("finishedAt", LocalDateTime.now().toString());
            state.expire(taskTtl);
        } catch (Exception e) {
            log.warn("图谱回填终态回写失败（残留 RUNNING 至 TTL 过期）: {}", e.getMessage());
        }
    }

    /** 任务视图：无任务态返回空 */
    public Optional<GraphBackfillView> view(String tenantId) {
        RMap<String, String> state = stateMap(tenantId);
        String status = state.get("status");
        if (status == null) {
            return Optional.empty();
        }
        return Optional.of(new GraphBackfillView(
            status,
            parseInt(state.get("total")),
            counter(tenantId, "succeeded").get(),
            counter(tenantId, "failed").get(),
            parseTime(state.get("startedAt")),
            parseTime(state.get("finishedAt"))));
    }

    private RMap<String, String> stateMap(String tenantId) {
        return redissonClient.getMap(KEY_PREFIX + tenantId, StringCodec.INSTANCE);
    }

    private RAtomicLong counter(String tenantId, String name) {
        return redissonClient.getAtomicLong(KEY_PREFIX + tenantId + ":cnt:" + name);
    }

    private static int parseInt(String raw) {
        try {
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static LocalDateTime parseTime(String raw) {
        try {
            return raw == null ? null : LocalDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
