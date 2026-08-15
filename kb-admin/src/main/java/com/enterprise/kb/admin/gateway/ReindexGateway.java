package com.enterprise.kb.admin.gateway;

import java.util.concurrent.CompletableFuture;

/**
 * 文档重入库网关（Phase 4 簇③ 4.5）——kb-admin 对重入库能力的依赖倒置抽象。
 *
 * <p>kb-admin 不可依赖 kb-api（kb-api 聚合 kb-admin，反向依赖成环），而重入库
 * 编排（原子占用 + 进度回调 + 指标终态计数）位于 kb-api 的 DocumentService。
 * 故本接口定义于 kb-admin，实现 Bean（DocumentReindexGateway）由 kb-api 提供并
 * 委派 DocumentService.reparse——重建编排零重复代码复用 C1 增量链路。
 *
 * <p>实现约定：
 * <ul>
 *   <li>同步快速失败：文档不存在/非本租户/状态不可重入库 → 抛 BusinessException
 *       （DOC_NOT_FOUND / DOC_FORBIDDEN / DOC_NOT_READY），调用方记 skipped；</li>
 *   <li>返回值：ETL 终态帧回调完成——true = COMPLETED，false = FAILED。</li>
 * </ul>
 */
public interface ReindexGateway {

    /**
     * 发起单文档重入库（异步 ETL），返回终态结果 future。
     *
     * @param parseRoute 强制路由（null = 复现文档原始路由，同 reparse 端点语义）
     */
    CompletableFuture<Boolean> reparse(String docId, String tenantId, String parseRoute);
}
