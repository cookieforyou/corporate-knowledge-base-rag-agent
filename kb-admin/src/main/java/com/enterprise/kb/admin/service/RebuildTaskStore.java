package com.enterprise.kb.admin.service;

import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.dto.RebuildTaskView.FailureView;

import java.util.List;
import java.util.Optional;

/**
 * 重建任务表存储抽象（v2.36）。
 *
 * <p>v2.33 形态为 IndexRebuildService 内存 LinkedHashMap——重启丢失且任务列表
 * 全局共享（跨租户可见失败明细 docId）。v2.36 迁 Redis 并租户域隔离；接口抽象
 * 保留编排与存储的测试接缝。实现见 {@link RedisRebuildTaskStore}。
 *
 * <p><b>租户纪律</b>：{@code find} 要求调用方传入期望租户，不存在与跨租户一律
 * 返回空（不泄露存在性，同 CHUNK_NOT_FOUND / AUDIT_LOG_NOT_FOUND 语义）；
 * {@code listByTenant} 只返回本租户任务。
 */
public interface RebuildTaskStore {

    /**
     * 登记新任务（RUNNING 态 + 前置 skipped 明细）。
     * 存储故障 fail-closed 抛 {@code REBUILD_STORE_UNAVAILABLE}（任务不可无表启动）。
     */
    void create(String tenantId, String taskId, int total, List<FailureView> initialSkipped);

    /** 记录单文档成功（原子计数）；存储故障仅告警不阻断在途重建 */
    void recordSuccess(String taskId);

    /** 记录单文档失败（原子计数 + 明细追加）；存储故障仅告警不阻断在途重建 */
    void recordFailure(String taskId, String docId, String reason);

    /** 记录单文档跳过（原子计数 + 明细追加）；存储故障仅告警不阻断在途重建 */
    void recordSkipped(String taskId, String docId, String reason);

    /** 任务终态：COMPLETED + finishedAt；存储故障仅告警（任务残留 RUNNING 至 TTL） */
    void finish(String taskId);

    /** 任务详情：不存在或与期望租户不符一律空（不泄露存在性） */
    Optional<RebuildTaskView> find(String taskId, String requiredTenantId);

    /** 租户任务列表（insertion order，最新在后）；过期残留条目惰性清理 */
    List<RebuildTaskView> listByTenant(String tenantId);
}
