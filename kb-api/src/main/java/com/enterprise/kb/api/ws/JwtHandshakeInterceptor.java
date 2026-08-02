package com.enterprise.kb.api.ws;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手 JWT 鉴权（任务 2.13 验收项：WS 握手经 JWT 校验）
 *
 * <p>浏览器 WebSocket API 无法携带 Authorization 头，令牌经 {@code ?token=} 查询
 * 参数传递，复用 OAuth2 Resource Server 的 {@link JwtDecoder}（与 /api/** 同一信任链）
 * 校验。校验通过将会话身份写入握手属性；失败拒绝握手（HTTP 403）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtDecoder jwtDecoder;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            token = servletRequest.getServletRequest().getParameter("token");
        }
        if (token == null || token.isBlank()) {
            log.warn("WS 握手拒绝：缺少 token 参数");
            return false;
        }
        try {
            Jwt jwt = jwtDecoder.decode(token);
            attributes.put("userId", jwt.getSubject());
            attributes.put("tenantId", jwt.getClaimAsString("owner"));
            return true;
        } catch (Exception e) {
            log.warn("WS 握手拒绝：JWT 校验失败（{}）", e.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
