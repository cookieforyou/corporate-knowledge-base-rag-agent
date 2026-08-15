package com.enterprise.kb.api.service;

import com.enterprise.kb.admin.gateway.ReindexGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 重入库网关实现（Phase 4 簇③ 4.5）——kb-api 侧唯一实现 Bean，委派
 * {@link DocumentService#reparse}（C1 增量链路：原子占用 + 蓝绿管线 +
 * 进度回调指标），供 kb-admin IndexRebuildService 经接口消费。
 *
 * <p>依赖倒置动因：kb-api 聚合 kb-admin（ fat jar 装配），kb-admin 不可反向
 * 依赖 kb-api——接口定义在 kb-admin，实现在 kb-api，重建编排零重复代码。
 */
@Component
@RequiredArgsConstructor
public class DocumentReindexGateway implements ReindexGateway {

    private final DocumentService documentService;

    @Override
    public CompletableFuture<Boolean> reparse(String docId, String tenantId, String parseRoute) {
        return documentService.reparse(docId, tenantId, parseRoute);
    }
}
