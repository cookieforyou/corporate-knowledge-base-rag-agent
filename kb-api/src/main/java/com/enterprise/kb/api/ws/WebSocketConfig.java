package com.enterprise.kb.api.ws;

import com.enterprise.kb.etl.pipeline.EtlProgressRedisWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * WebSocket 装配（任务 2.13）：ETL 进度端点注册 + Redis Pub/Sub 订阅分发
 *
 * <p>数据流：ETL 进度 → Redis Hash（状态）+ Pub/Sub 频道 etl:progress（实时）→
 * 本容器的监听器按 docId 分发 → {@link EtlProgressWebSocketHandler} 推送订阅会话。
 *
 * <p>端点鉴权由 {@link JwtHandshakeInterceptor} 在握手层完成，故 SecurityConfig
 * 对 /ws/** 放行（filter chain 不再重复校验）。
 *
 * <p><b>2026-08-03 修复</b>：{@code @EnableWebSocket} 缺失——仅实现
 * WebSocketConfigurer 不会注册 WebSocketHandlerMapping，/ws/** 落空到静态资源
 * 处理器（NoResourceFoundException），前端握手失败、进度停在首帧。
 */
@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final JsonMapper jsonMapper;
    private final EtlProgressWebSocketHandler progressHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    /** 允许的前端来源（Vite dev server 默认 5173；生产经 WS_ALLOWED_ORIGINS 注入） */
    @Value("${app.ws.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(progressHandler, "/ws/etl/progress")
            .addInterceptors(jwtHandshakeInterceptor)
            .setAllowedOrigins(allowedOrigins);
    }

    /** Redis Pub/Sub 订阅：etl:progress 频道 → 按 docId 分发至 WebSocket 会话 */
    @Bean
    public RedisMessageListenerContainer etlProgressListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            try {
                String docId = jsonMapper.readTree(json).path("docId").asText(null);
                if (docId != null) {
                    progressHandler.broadcast(docId, json);
                }
            } catch (Exception e) {
                log.warn("ETL 进度消息分发失败: {}", e.getMessage());
            }
        }, new PatternTopic(EtlProgressRedisWriter.CHANNEL));
        return container;
    }
}
