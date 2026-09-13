package com.enterprise.kb.ai.agent.a2a;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
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
 * A2aIdentityGuard 单测（Phase5簇⑥ 批2，McpIdentityGuardTest 同构）——JWT 捕获
 * fail-closed 三层 + scope 治理双形态。
 */
class A2aIdentityGuardTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** principal 为 Jwt 的已认证 Authentication（守卫只契约 principal 类型，不绑定具体令牌类） */
    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext()
            .setAuthentication(new TestingAuthenticationToken(jwt, null, Constants.MessageRole.USER));
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "none");
    }

    @Test
    void missingAuthenticationRejectedFailClosed() {
        A2aIdentityGuard guard = new A2aIdentityGuard("");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(Constants.ErrorCodes.IDENTITY_INCOMPLETE);
    }

    @Test
    void missingOwnerClaimRejectedFailClosed() {
        authenticate(jwt().claim(Constants.JwtClaims.SUB, "u-1").build());
        A2aIdentityGuard guard = new A2aIdentityGuard("");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(Constants.ErrorCodes.IDENTITY_INCOMPLETE);
    }

    @Test
    void validJwtMaterializesRetrievalContext() {
        authenticate(jwt().claim(Constants.JwtClaims.OWNER, "t-1").claim(Constants.JwtClaims.SUB, "u-1").build());
        A2aIdentityGuard guard = new A2aIdentityGuard("");

        RetrievalContext ctx = guard.requireIdentity();

        assertThat(ctx.getTenantId()).isEqualTo("t-1");
        assertThat(ctx.getUserId()).isEqualTo("u-1");
    }

    @Test
    void blankSubFallsBackToAnonymous() {
        authenticate(jwt().claim(Constants.JwtClaims.OWNER, "t-1").build());
        A2aIdentityGuard guard = new A2aIdentityGuard("");

        assertThat(guard.requireIdentity().getUserId()).isEqualTo("anonymous");
    }

    @Test
    void scopeRequiredPresentInCollectionClaimPasses() {
        authenticate(jwt().claim(Constants.JwtClaims.OWNER, "t-1").claim(Constants.JwtClaims.SUB, "u-1")
            .claim("scope", List.of("kb.read", "kb.write")).build());
        A2aIdentityGuard guard = new A2aIdentityGuard("kb.read");

        assertThat(guard.requireIdentity().getTenantId()).isEqualTo("t-1");
    }

    @Test
    void scopeRequiredMissingDenied() {
        authenticate(jwt().claim(Constants.JwtClaims.OWNER, "t-1").claim(Constants.JwtClaims.SUB, "u-1")
            .claim("scope", "other.scope").build());
        A2aIdentityGuard guard = new A2aIdentityGuard("kb.read");

        assertThatThrownBy(guard::requireIdentity)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo(Constants.ErrorCodes.A2A_SCOPE_DENIED);
    }
}
