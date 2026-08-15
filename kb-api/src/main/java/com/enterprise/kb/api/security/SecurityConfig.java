package com.enterprise.kb.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 Resource Server 安全配置
 *
 * <p>验证 JWT，保护 /api/** 端点。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // metrics/prometheus 放行供 Prometheus 抓取（3.13 验收「可采集」）：
                // 指标不含租户标签与敏感内容（标签纪律见 AiBusinessMetrics）；
                // 生产加固可选 IP 白名单（独立 SecurityFilterChain），现阶段与 health/info 同级放行
                .requestMatchers("/actuator/health", "/actuator/info",
                    "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus").permitAll()
                // WebSocket 端点放行 filter chain：鉴权在握手层经 JwtHandshakeInterceptor
                // 复用同一 JwtDecoder 完成（2.13；浏览器 WS API 无法携带 Authorization 头）
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                // MCP Server 端点（簇⑤ 4.10，Streamable HTTP）：JWT bearer 鉴权同 /api/**；
                // 租户/scope 治理在工具调用层经 McpIdentityGuard fail-closed 二次收敛
                .requestMatchers("/mcp").authenticated()
                .anyRequest().denyAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
