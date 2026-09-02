package com.enterprise.kb.api.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * OAuth2 Resource Server 安全配置
 *
 * <p>验证 JWT，保护 /api/** 端点。</p>
 *
 * <p><b>安全簇② B1/B4（2026-08-17）</b>：平台层加固——</p>
 * <ul>
 *   <li><b>B1 CORS 策略显式化</b>：此前 HTTP 层零 CORS 配置（仅 WS 握手有
 *       allowedOrigins）。白名单经 {@code app.cors.allowed-origins} 收口
 *       （env APP_CORS_ALLOWED_ORIGINS 追加生产域），白名单外来源预检直接拒绝；</li>
 *   <li><b>B4 安全响应头显式化</b>：Spring Security 默认头（X-Content-Type-Options /
 *       Cache-Control 等）保持生效，补 CSP（纯 API 服务，default-src 'none'）+
 *       frameOptions DENY + HSTS 三项显式钉死。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** CORS 预检缓存秒数（浏览器侧 OPTIONS 结果缓存窗口） */
    private static final long CORS_MAX_AGE_SECONDS = 3600L;

    /** HSTS max-age（一年，含子域；仅 HTTPS 响应生效，dev HTTP 下浏览器自动忽略） */
    private static final long HSTS_MAX_AGE_SECONDS = 31536000L;

    /**
     * 允许的前端来源（安全簇② B1；默认 Vite dev server 5173，
     * 生产经 APP_CORS_ALLOWED_ORIGINS 注入——与 WS 侧 WS_ALLOWED_ORIGINS 同款 env 通道，
     * 两键独立：HTTP CORS 与 WS 握手 Origin 策略解耦，可分别收口）
     */
    private final String[] allowedOrigins;

    public SecurityConfig(@Value("${app.cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // metrics/prometheus 放行供 Prometheus 抓取（3.13 验收「可采集」）：
                // 指标不含租户标签与敏感内容（标签纪律见 AiBusinessMetrics）；
                // 生产加固可选 IP 白名单（独立 SecurityFilterChain），现阶段与 health/info 同级放行；
                // 暴露面已在 application.yml include 白名单显式钉死（仅四项，其余端点不暴露）
                .requestMatchers("/actuator/health", "/actuator/info",
                    "/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus").permitAll()
                // WebSocket 端点放行 filter chain：鉴权在握手层经 JwtHandshakeInterceptor
                // 复用同一 JwtDecoder 完成（2.13；浏览器 WS API 无法携带 Authorization 头）
                .requestMatchers("/ws/**").permitAll()
                // 运维面端点提级（前端鉴权批，2026-09-02）：kb-admin 六 Controller 统一
                // 收口于 /api/v1/admin/**（chunks/rebuild/audit-logs/badcase/guardrail/
                // graph/feedback-export）——isAdmin claim 映射 ROLE_ADMIN 后此处单点守卫，
                // 统计 /api/v1/stats 保持租户全员；语义 = 租户内运维权限分级，租户隔离
                // fail-closed 基线不动（owner 单租户锚点，无跨租户视图）
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                // MCP Server 端点（簇⑤ 4.10，Streamable HTTP）：JWT bearer 鉴权同 /api/**；
                // 租户/scope 治理在工具调用层经 McpIdentityGuard fail-closed 二次收敛
                .requestMatchers("/mcp").authenticated()
                .anyRequest().denyAll()
            )
            // B1：CORS 白名单——CorsFilter 经 security 集成位点装配，先于鉴权过滤器，
            // 白名单外来源的跨域请求（含预检）在 CORS 层即被拒绝（无 CORS 响应头 → 浏览器拦截）
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            // B4：安全响应头——默认头保持开启（X-Content-Type-Options nosniff /
            // Cache-Control / X-XSS-Protection 等），另显式钉三项：
            // CSP default-src 'none'（纯 API 无 HTML 渲染面）+ frameOptions DENY + HSTS
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                    .includeSubDomains(true))
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /**
     * JWT → GrantedAuthorities 映射（前端鉴权批，2026-09-02）：Casdoor 把用户对象字段
     * 直入 JWT payload，{@code isAdmin} 布尔即超管标记（租户 org 内管理员；
     * built-in 全局 admin 的 owner=built-in 落空租户，无业务数据面）——映射为
     * {@code ROLE_ADMIN} 供 {@code /api/v1/admin/**} filter-chain 与方法级
     * {@code @PreAuthorize} 消费；claim 缺失/false 走缺省空权限（纯租户用户）。
     *
     * <p>标准 SCOPE_/authorities 通道不适用：Casdoor access token 无标准 scope claim，
     * 自定义 claim 映射是 Resource Server 唯一权威形态。</p>
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Boolean isAdmin = jwt.getClaimAsBoolean("isAdmin");
            return Boolean.TRUE.equals(isAdmin)
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of();
        });
        return converter;
    }

    /**
     * CORS 策略源（安全簇② B1）：白名单 origin + 业务方法/头收敛。
     *
     * <p>allowCredentials 显式钉 false（CorsConfiguration 缺省为 null，语义虽等价
     * 不显式化）——全链 JWT bearer 头鉴权、无 Cookie 会话，无需凭证跨域；
     * origin 白名单不允许通配（setAllowedOrigins 语义），生产多域经 env 逗号分隔注入。</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(CORS_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
