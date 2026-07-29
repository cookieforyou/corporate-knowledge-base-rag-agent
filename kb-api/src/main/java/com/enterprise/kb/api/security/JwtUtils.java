package com.enterprise.kb.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * JWT 工具 — 从当前请求的 SecurityContext 中提取用户信息
 *
 * <p>基于 Casdoor JWT payload 实际字段映射：</p>
 * <pre>
 * sub   → userId  (UUID)
 * name  → username (如 user_10001)
 * owner → tenantId（如 tenant_002）
 * </pre>
 */
@Component
public class JwtUtils {

    /** 当前用户 ID（Casdoor sub = UUID） */
    public String getCurrentUserId() {
        return getJwt()
            .map(jwt -> jwt.getClaimAsString("sub"))
            .orElse("anonymous");
    }

    /** 当前租户 ID（Casdoor owner） */
    public String getCurrentTenantId() {
        return getJwt()
            .map(jwt -> jwt.getClaimAsString("owner"))
            .orElse("default");
    }

    /** 当前用户名（Casdoor name） */
    public String getCurrentUsername() {
        return getJwt()
            .map(jwt -> jwt.getClaimAsString("name"))
            .orElse("anonymous");
    }

    /** 当前用户展示名（Casdoor displayName） */
    public String getCurrentDisplayName() {
        return getJwt()
            .map(jwt -> jwt.getClaimAsString("displayName"))
            .or(() -> getJwt().map(jwt -> jwt.getClaimAsString("name")))
            .orElse("anonymous");
    }

    private Optional<Jwt> getJwt() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .filter(Authentication::isAuthenticated)
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof Jwt)
            .map(principal -> (Jwt) principal);
    }
}
