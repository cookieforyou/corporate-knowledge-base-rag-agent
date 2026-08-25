package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.GraphBackfillRequest;
import com.enterprise.kb.admin.dto.GraphBackfillView;
import com.enterprise.kb.admin.service.GraphBackfillService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图谱运维 API（簇④ 5.1 批3）：存量语料图专项回填。
 *
 * <p>与图谱域全族同条件装配——{@code rag.graph.enabled=false} 时整个控制器
 * 缺位（端点 404），关闭态零形态变化；租户守卫同 {@code RebuildController}
 * 口径（JWT owner claim，fail-closed）。
 */
@RestController
@RequestMapping("/api/v1/admin/graph")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "enabled", havingValue = "true")
public class GraphAdminController {

    private final GraphBackfillService graphBackfillService;

    /** 发起回填：body 缺省/docIds 空 = 租户全量待回填（PENDING/FAILED）；指定 = 目标增量 */
    @PostMapping("/backfill")
    public ApiResponse<GraphBackfillView> backfill(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestBody(required = false) GraphBackfillRequest request) {
        List<String> docIds = request != null ? request.docIds() : null;
        return ApiResponse.success(graphBackfillService.start(requireTenantId(jwt), docIds));
    }

    /** 回填任务视图（单租户单任务；无任务态返回空态视图） */
    @GetMapping("/backfill")
    public ApiResponse<GraphBackfillView> backfillStatus(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(graphBackfillService.status(requireTenantId(jwt)));
    }

    /** 租户守卫（fail-closed）：同 RebuildController 口径 */
    private static String requireTenantId(Jwt jwt) {
        String tenantId = jwt != null ? jwt.getClaimAsString("owner") : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
