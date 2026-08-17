package com.enterprise.kb.api.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * chat/SSE 请求体大小护栏（安全簇② B2，2026-08-17）
 *
 * <p>multipart 上限只约束上传通道；chat JSON 请求体（/api/v1/chat/**）此前
 * 无任何大小护栏——超大 body 整体进内存再解析。本过滤器按 Content-Length
 * 先行拦截，超限直接 413，不进后续链路。</p>
 *
 * <p><b>边界声明</b>：仅校验声明式 Content-Length；chunked（无 Content-Length）
 * 请求不拦——chat 端点客户端均为浏览器 fetch/axios（声明式头）与后端集成方，
 * chunked 超大载荷场景不在当前风险面，登记留观察。</p>
 */
@Component
public class ChatBodyLimitFilter extends OncePerRequestFilter {

    private final long maxBodyBytes;

    public ChatBodyLimitFilter(
            @Value("${app.chat.max-body-size:1MB}") DataSize maxBodySize) {
        this.maxBodyBytes = maxBodySize.toBytes();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/chat");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            // ApiResponse 同形（code/message，data NON_NULL 省略）——过滤器层
            // 不依赖 JsonMapper Bean，字面直写保持自包含
            response.getWriter().write("{\"code\":413,\"message\":\"请求体超过大小上限\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
