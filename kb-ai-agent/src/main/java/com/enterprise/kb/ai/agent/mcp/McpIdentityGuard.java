package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MCP 调用身份守卫（Phase 4 簇⑤ 4.10）——请求线程 JWT 捕获 → RetrievalContext 参数链。
 *
 * <p><b>形态</b>：MCP Streamable HTTP 端点（/mcp）经 SecurityConfig authenticated
 * 拦截，OAuth2 Resource Server 解析 JWT 填充 SecurityContext；工具方法在请求线程
 * 执行，本守卫捕获 Jwt principal 并**立即物化为纯实例 RetrievalContext**——其后
 * 检索/对话链全经参数链传递（不用 ThreadLocal/@RequestScope，请求状态传递纪律
 * 同 AgentController）。
 *
 * <p><b>fail-closed 三层</b>（同 AgentController/RetrievalDebugController 口径）：
 * ① JWT 缺失/非 Jwt principal → IDENTITY_INCOMPLETE；② owner claim（tenantId）
 * 空白 → IDENTITY_INCOMPLETE（绝不以无过滤形态进检索链）；③ scope 治理——
 * {@code rag.mcp.scope.required} 非空时 JWT scope 声明须包含之，否则
 * MCP_SCOPE_DENIED（Casdoor 应用级 scope 配置的治理抓手；默认空 = 仅租户
 * 纪律，兼容存量令牌形态）。
 *
 * <p><b>claim 映射同源 Casdoor 口径</b>：owner→tenantId、sub→userId。不复用
 * kb-api JwtUtils（kb-api 聚合本模块，反向依赖成环，同 kb-admin 纪律）。
 */
@Component
public class McpIdentityGuard {

    private final String requiredScope;

    public McpIdentityGuard(@Value("${rag.mcp.scope.required:}") String requiredScope) {
        this.requiredScope = requiredScope;
    }

    /** 捕获请求线程 JWT 身份 → 纯实例检索上下文（tenantId 完整性 fail-closed） */
    public RetrievalContext requireIdentity() {
        Jwt jwt = requireJwt();
        String tenantId = jwt.getClaimAsString("owner");
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：缺少租户信息");
        }
        requireScope(jwt);
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        String userId = jwt.getClaimAsString("sub");
        ctx.setUserId(userId != null && !userId.isBlank() ? userId : "anonymous");
        return ctx;
    }

    // ── 内部方法 ──

    private static Jwt requireJwt() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getPrincipal)
            .filter(Jwt.class::isInstance)
            .map(Jwt.class::cast)
            .orElseThrow(() -> new BusinessException("IDENTITY_INCOMPLETE", "MCP 调用缺少 JWT 身份"));
    }

    /** scope 治理：required 配置为空 = 仅租户纪律；非空则 JWT scope 声明须包含 */
    private void requireScope(Jwt jwt) {
        if (requiredScope == null || requiredScope.isBlank()) {
            return;
        }
        if (!scopesOf(jwt).contains(requiredScope)) {
            throw new BusinessException("MCP_SCOPE_DENIED", "MCP 调用缺少授权 scope: " + requiredScope);
        }
    }

    /** scope 声明容错解析：Casdoor 形态兼容 Collection / 空格分隔字符串 */
    private static List<String> scopesOf(Jwt jwt) {
        Object claim = jwt.getClaim("scope");
        if (claim instanceof Collection<?> collection) {
            return collection.stream().map(String::valueOf).toList();
        }
        if (claim instanceof String text) {
            return List.of(text.trim().split("\\s+"));
        }
        return List.of();
    }
}
