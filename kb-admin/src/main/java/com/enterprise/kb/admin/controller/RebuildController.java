package com.enterprise.kb.admin.controller;

import com.enterprise.kb.admin.dto.RebuildRequest;
import com.enterprise.kb.admin.dto.RebuildTaskView;
import com.enterprise.kb.admin.service.IndexRebuildService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 索引重建 API（Phase 4 簇③ 4.5）——全量/目标文档重建任务编排与查询。
 *
 * <p>任务为异步形态：POST 返回 taskId 即受理，进度经任务查询端点轮询；
 * 任务表内存态（重启丢失，重建幂等可重发，见 IndexRebuildService 注记）。
 */
@RestController
@RequestMapping("/api/v1/admin/rebuild")
@RequiredArgsConstructor
public class RebuildController {

    private final IndexRebuildService indexRebuildService;

    /** 发起重建：body 缺省/docIds 空 = 租户全量；指定 docIds = 目标增量（漂移修复） */
    @PostMapping
    public ApiResponse<RebuildTaskView> start(@AuthenticationPrincipal Jwt jwt,
                                              @RequestBody(required = false) RebuildRequest request) {
        List<String> docIds = request != null ? request.docIds() : null;
        return ApiResponse.success(indexRebuildService.start(requireTenantId(jwt), docIds));
    }

    /** 重建任务列表（内存任务表，近 20 条，insertion order） */
    @GetMapping("/tasks")
    public ApiResponse<List<RebuildTaskView>> tasks(@AuthenticationPrincipal Jwt jwt) {
        requireTenantId(jwt);
        return ApiResponse.success(indexRebuildService.list());
    }

    /** 重建任务详情 */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<RebuildTaskView> task(@AuthenticationPrincipal Jwt jwt,
                                             @PathVariable String taskId) {
        requireTenantId(jwt);
        RebuildTaskView view = indexRebuildService.detail(taskId);
        if (view == null) {
            throw new BusinessException("REBUILD_TASK_NOT_FOUND", "重建任务不存在: " + taskId);
        }
        return ApiResponse.success(view);
    }

    /** 租户守卫（fail-closed）：同 ChunkAdminController 口径 */
    private static String requireTenantId(Jwt jwt) {
        String tenantId = jwt != null ? jwt.getClaimAsString("owner") : null;
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
