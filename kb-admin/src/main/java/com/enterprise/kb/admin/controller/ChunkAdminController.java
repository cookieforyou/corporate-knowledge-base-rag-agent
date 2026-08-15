package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.ChunkUpdateRequest;
import com.enterprise.kb.admin.dto.ChunkView;
import com.enterprise.kb.admin.service.ChunkOpsService;
import com.enterprise.kb.admin.service.ChunkOpsService.ChunkOpsResult;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chunk 运维 API（Phase 4 簇③ 4.4）——编辑（异步重嵌入）/ 软删 / 恢复。
 *
 * <p><b>租户守卫</b>：经 {@code @AuthenticationPrincipal Jwt} 直接消费 OAuth2
 * Resource Server 解析结果（kb-admin 不依赖 kb-api，不复用其 JwtUtils），
 * claim 映射同源 Casdoor 口径（owner → tenantId）；缺失 fail-closed
 * IDENTITY_INCOMPLETE，与既有 Controller 同款语义。
 *
 * <p>Chunk 列表查询不经本 Controller——复用既有
 * {@code GET /api/v1/documents/{id}/chunks}（观测台数据源，含所有权校验）。
 */
@RestController
@RequestMapping("/api/v1/admin/chunks")
@RequiredArgsConstructor
public class ChunkAdminController {

    private final ChunkOpsService chunkOpsService;

    /** 编辑 Chunk 文本：同源消毒 → PG 同步更新 → 异步重嵌入（双向量库 + ES） */
    @PutMapping("/{chunkId}")
    public ApiResponse<ChunkView> edit(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable String chunkId,
                                       @Valid @RequestBody ChunkUpdateRequest request) {
        ChunkOpsResult result = chunkOpsService.edit(chunkId, requireTenantId(jwt), request.content());
        return ApiResponse.success(ChunkView.from(result.chunk()));
    }

    /** 软删除（C1 管道：PG is_deleted + ES markDeleted + 向量物理删），幂等 */
    @DeleteMapping("/{chunkId}")
    public ApiResponse<ChunkView> softDelete(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String chunkId) {
        ChunkOpsResult result = chunkOpsService.softDelete(chunkId, requireTenantId(jwt));
        return ApiResponse.success(ChunkView.from(result.chunk()));
    }

    /** 恢复软删 Chunk：PG 复活 + 异步重嵌入（向量已删必经重嵌入，ES 覆写复位） */
    @PostMapping("/{chunkId}/restore")
    public ApiResponse<ChunkView> restore(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable String chunkId) {
        ChunkOpsResult result = chunkOpsService.restore(chunkId, requireTenantId(jwt));
        return ApiResponse.success(ChunkView.from(result.chunk()));
    }

    /** 租户守卫（fail-closed）：JWT 缺失或 owner claim 空白 → IDENTITY_INCOMPLETE */
    private static String requireTenantId(Jwt jwt) {
        String tenantId = jwt != null ? jwt.getClaimAsString("owner") : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
