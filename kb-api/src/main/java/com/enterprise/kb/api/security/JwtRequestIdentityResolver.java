package com.enterprise.kb.api.security;

import com.enterprise.kb.ai.advisor.RequestIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JWT 身份解析实现（2.11）—— 桥接 kb-api 的 JwtUtils 与 kb-ai-core 的
 * RequestIdentityResolver 抽象（模块依赖方向：kb-ai-core ← kb-api）
 */
@Component
@RequiredArgsConstructor
public class JwtRequestIdentityResolver implements RequestIdentityResolver {

    private final JwtUtils jwtUtils;

    @Override
    public String getTenantId() {
        return jwtUtils.getCurrentTenantId();
    }

    @Override
    public String getUserId() {
        return jwtUtils.getCurrentUserId();
    }
}
