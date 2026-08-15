package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * McpIdentityGuard 单测（簇⑤ 4.10）——JWT 捕获 fail-closed 三层 + scope 治理双形态。
 */
class McpIdentityGuardTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** principal 为 Jwt 的已认证 Authentication（守卫只契约 principal 类型，不绑定具体令牌类） */
    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken(jwt, null, "USER"));
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    @Test
    void missingAuthenticationRejectedFailClosed() {
        McpIdentityGuard guard = new McpIdentityGuard("");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
    }

    @Test
    void missingOwnerClaimRejectedFailClosed() {
        authenticate(jwt().claim("sub", "u-1").build());
        McpIdentityGuard guard = new McpIdentityGuard("");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("IDENTITY_INCOMPLETE");
    }

    @Test
    void validJwtMaterializesRetrievalContext() {
        authenticate(jwt().claim("owner", "t-1").claim("sub", "u-1").build());
        McpIdentityGuard guard = new McpIdentityGuard("");

        RetrievalContext ctx = guard.requireIdentity();

        assertThat(ctx.getTenantId()).isEqualTo("t-1");
        assertThat(ctx.getUserId()).isEqualTo("u-1");
    }

    @Test
    void blankSubFallsBackToAnonymous() {
        authenticate(jwt().claim("owner", "t-1").build());
        McpIdentityGuard guard = new McpIdentityGuard("");

        assertThat(guard.requireIdentity().getUserId()).isEqualTo("anonymous");
    }

    @Test
    void scopeRequiredPresentInCollectionClaimPasses() {
        authenticate(jwt().claim("owner", "t-1").claim("sub", "u-1")
            .claim("scope", List.of("kb.read", "kb.write")).build());
        McpIdentityGuard guard = new McpIdentityGuard("kb.read");

        assertThat(guard.requireIdentity().getTenantId()).isEqualTo("t-1");
    }

    @Test
    void scopeRequiredPresentInSpaceDelimitedStringClaimPasses() {
        authenticate(jwt().claim("owner", "t-1").claim("sub", "u-1")
            .claim("scope", "kb.read kb.write").build());
        McpIdentityGuard guard = new McpIdentityGuard("kb.write");

        assertThat(guard.requireIdentity().getTenantId()).isEqualTo("t-1");
    }

    @Test
    void scopeRequiredMissingDenied() {
        authenticate(jwt().claim("owner", "t-1").claim("sub", "u-1")
            .claim("scope", "other.scope").build());
        McpIdentityGuard guard = new McpIdentityGuard("kb.read");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_SCOPE_DENIED");
    }

    @Test
    void scopeRequiredWithoutAnyScopeClaimDenied() {
        authenticate(jwt().claim("owner", "t-1").claim("sub", "u-1").build());
        McpIdentityGuard guard = new McpIdentityGuard("kb.read");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_SCOPE_DENIED");
    }
}
