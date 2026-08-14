package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.DocumentProcessingView;
import com.enterprise.kb.api.dto.StatsOverview;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.StatsService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库统计 Controller（Phase 4 簇② 任务 4.6：运维仪表盘数据接口）
 *
 * <p>认证经 SecurityConfig /api/** JWT 统一拦截；身份守卫与 FeedbackController
 * 同款（tenantId 缺失拒绝，fail-closed，3.9 纪律）。统计全部按租户隔离。
 */
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final JwtUtils jwtUtils;

    /**
     * 知识库统计总览（仪表盘首屏）
     *
     * <pre>
     * GET /api/v1/stats/overview
     * → { "code": 200, "data": { "documentTotal", "documentsByStatus",
     *      "chunkTotal", "documentsByParseRoute", "dailyIngestion"[14d] } }
     * </pre>
     */
    @GetMapping("/overview")
    public ApiResponse<StatsOverview> overview() {
        return ApiResponse.success(statsService.overview(requireTenantId()));
    }

    /**
     * 文档解析状态（处理中三态计数 + 清单，运维监控面板）
     *
     * <pre>
     * GET /api/v1/stats/documents/processing
     * → { "code": 200, "data": { "counts", "documents"[] } }
     * </pre>
     */
    @GetMapping("/documents/processing")
    public ApiResponse<DocumentProcessingView> processing() {
        return ApiResponse.success(statsService.processing(requireTenantId()));
    }

    /** 身份守卫（3.9 同款）：tenantId 缺失即拒，统计可见域依赖租户 */
    private String requireTenantId() {
        String tenantId = jwtUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
