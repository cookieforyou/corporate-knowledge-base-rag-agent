package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.HistoryMessageItem;
import com.enterprise.kb.api.dto.SessionItem;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.ChatSessionService;
import com.enterprise.kb.commons.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import com.enterprise.kb.commons.dto.ApiResponse;

/**
 * 历史会话 Controller（3.15 补齐，设计文档附录 C 锚点；路径前缀沿用既有扁平惯例）
 *
 * <p>会话列表 / 历史消息（含 citations 溯源恢复与反馈回显）/ 删除。
 * 数据面 tenant+user 双过滤 fail-closed 在 {@link ChatSessionService} 内收敛；
 * 本层只做身份完整性守卫（与 AgentController 同款：owner claim 缺失即拒）。
 */
@RestController
@RequestMapping("/api/v1/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService chatSessionService;
    private final JwtUtils jwtUtils;

    /** 会话列表：按 updated_at 倒序，page 0 基页码，size 上限服务层截断 */
    @GetMapping
    public ApiResponse<List<SessionItem>> listSessions(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {
        String tenantId = requireTenantId();
        return ApiResponse.success(chatSessionService.listSessions(
            tenantId, jwtUtils.getCurrentUserId(), page, size));
    }

    /** 历史消息：归属校验 fail-closed；assistant 消息附溯源载荷/traceId/反馈回显 */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<HistoryMessageItem>> sessionMessages(@PathVariable String id) {
        String tenantId = requireTenantId();
        return ApiResponse.success(chatSessionService.loadMessages(
            id, tenantId, jwtUtils.getCurrentUserId()));
    }

    /** 删除会话：硬删（kb_message 外键级联）+ 清 Redis 记忆（旁路） */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> deleteSession(@PathVariable String id) {
        String tenantId = requireTenantId();
        chatSessionService.deleteSession(id, tenantId, jwtUtils.getCurrentUserId());
        return ApiResponse.success(Map.of("deleted", true));
    }

    /** 身份完整性守卫（3.9+3.10 同纪律）：tenantId 缺失即拒，绝不放行无租户上下文的查询 */
    private String requireTenantId() {
        String tenantId = jwtUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
