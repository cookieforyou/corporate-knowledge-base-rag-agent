package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.dto.FeedbackItem;
import com.enterprise.kb.api.dto.FeedbackRequest;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.FeedbackService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbFeedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户反馈收集 Controller（3.17，验收 #13：点赞/点踩 → kb_feedback 落库 → Bad Case 可查询）
 *
 * <p>定位句柄来自对话链路（messageId/traceId 经 SSE DONE 帧 / 同步 /chat 响应送达）。
 * 认证经 SecurityConfig /api/** JWT 统一拦截；身份守卫与 AgentController 同款
 * （tenantId 缺失拒绝，fail-closed）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final JwtUtils jwtUtils;

    /**
     * 提交反馈（点赞/点踩 + 期望回答，按 messageId+userId upsert 可更改）
     *
     * <pre>
     * POST /api/v1/feedback
     * { "messageId": "必填", "traceId": "可选", "rating": "POSITIVE|NEGATIVE",
     *   "expectedAnswer": "可选", "tags": ["可选"] }
     * → { "code": 200, "data": { "feedbackId": "...", "rating": "..." } }
     * </pre>
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@RequestBody FeedbackRequest request) {
        String tenantId = requireTenantId();
        String userId = jwtUtils.getCurrentUserId();
        KbFeedback saved = feedbackService.submit(tenantId, userId, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feedbackId", saved.getId());
        data.put("rating", saved.getRating().name());
        return ApiResponse.success(data);
    }

    /**
     * Bad Case 查询：租户可见域反馈列表（rating/resolved 可选过滤，limit 默认 50 上限 100），
     * 附原始问答文本与 auditLogId（联查检索快照/prompt 全链路）
     */
    @GetMapping
    public ApiResponse<List<FeedbackItem>> search(
        @RequestParam(required = false) String rating,
        @RequestParam(required = false) Boolean resolved,
        @RequestParam(required = false) Integer limit) {
        String tenantId = requireTenantId();
        return ApiResponse.success(feedbackService.search(tenantId, rating, resolved, limit));
    }

    /** 身份守卫（3.9 同款）：tenantId 缺失即拒，反馈归属与查询可见域均依赖租户 */
    private String requireTenantId() {
        String tenantId = jwtUtils.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        return tenantId;
    }
}
