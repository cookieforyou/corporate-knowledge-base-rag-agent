package com.enterprise.kb.api.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig CORS 策略测试（安全簇② B1）：白名单语义钉死
 *
 * <p>filterChain 装配（headers/cors 集成位点）经 E2E 通道验证
 * （跨域请求拒止 + 响应头断言），本单测覆盖策略源确定性行为。</p>
 */
class SecurityConfigTest {

    @Test
    void whitelistedOriginAllowed() {
        SecurityConfig config = new SecurityConfig(new String[]{"http://localhost:5173"});
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/chat/stream"));

        assertThat(cors).isNotNull();
        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }

    @Test
    void foreignOriginRejected() {
        SecurityConfig config = new SecurityConfig(new String[]{"http://localhost:5173"});
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/anything"));

        assertThat(cors.checkOrigin("https://attacker.example")).isNull();
        assertThat(cors.checkOrigin("http://localhost:3000")).isNull();
        // origin 白名单不允许通配形态（credentials=false 下亦不放行 *）
        assertThat(cors.getAllowedOrigins()).doesNotContain("*");
    }

    @Test
    void multipleOriginsFromEnvStyleInjection() {
        SecurityConfig config = new SecurityConfig(
            new String[]{"http://localhost:5173", "https://kb.example.com"});
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/mcp"));

        assertThat(cors.checkOrigin("https://kb.example.com")).isEqualTo("https://kb.example.com");
        assertThat(cors.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }

    @Test
    void methodsHeadersAndCredentialsPinned() {
        SecurityConfig config = new SecurityConfig(new String[]{"http://localhost:5173"});
        CorsConfigurationSource source = config.corsConfigurationSource();

        CorsConfiguration cors = source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/x"));

        assertThat(cors.getAllowedMethods())
            .containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(cors.getAllowedHeaders())
            .containsExactly("Authorization", "Content-Type", "Accept");
        // 全链 JWT bearer 头鉴权、无 Cookie 会话——凭证跨域保持关闭
        assertThat(cors.getAllowCredentials()).isFalse();
        assertThat(cors.getMaxAge()).isEqualTo(3600L);
    }
}
