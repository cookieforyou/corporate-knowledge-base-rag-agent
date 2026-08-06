package com.enterprise.kb.api.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ETL 进度 WebSocket 端点（设计文档 9.6，任务 2.13）
 *
 * <p>订阅协议（文本帧 JSON）：
 * <ul>
 *   <li>连接参数 {@code ?docId=xxx} 自动订阅（单文档上传流主路径）；</li>
 *   <li>{@code {"action":"subscribe","docId":"..."}} / {@code unsubscribe} 显式订阅管理；</li>
 *   <li>服务端推送：{@link com.enterprise.kb.etl.pipeline.EtlProgress} JSON
 *       （docId/stage/documentCount/chunkCount/processedChunks/percentage）。</li>
 * </ul>
 *
 * <p>数据来源：Redis Pub/Sub 频道 {@code etl:progress}（ETL 侧写入，见
 * EtlProgressRedisWriter），按 docId 分发至订阅会话。WebSocketSession 发送
 * 非线程安全，按会话加锁串行化。
 */
@Slf4j
@Component
public class EtlProgressWebSocketHandler extends TextWebSocketHandler {

    /** docId → 订阅会话集合 */
    private final Map<String, Set<WebSocketSession>> subscriptions = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    public EtlProgressWebSocketHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String docId = queryParam(session, "docId");
        if (docId != null && !docId.isBlank()) {
            subscribe(session, docId);
        }
        log.debug("WS 会话建立: {}, 自动订阅 docId={}", session.getId(), docId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = jsonMapper.readTree(message.getPayload());
            String action = node.path("action").asString("");
            String docId = node.path("docId").asString(null);
            if (docId == null || docId.isBlank()) {
                return;
            }
            switch (action) {
                case "subscribe" -> subscribe(session, docId);
                case "unsubscribe" -> unsubscribe(session, docId);
                default -> log.debug("WS 未知动作: {}", action);
            }
        } catch (Exception e) {
            log.warn("WS 消息解析失败: {}", e.getMessage());
        }
    }

    /** 向订阅指定文档的全部会话推送进度 JSON（来自 Redis Pub/Sub） */
    public void broadcast(String docId, String progressJson) {
        Set<WebSocketSession> sessions = subscriptions.get(docId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(progressJson);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (Exception e) {
                log.debug("WS 推送失败（会话 {}）: {}", session.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscriptions.values().forEach(sessions -> sessions.remove(session));
        subscriptions.values().removeIf(Set::isEmpty);
        log.debug("WS 会话关闭: {} ({})", session.getId(), status);
    }

    private void subscribe(WebSocketSession session, String docId) {
        subscriptions.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    private void unsubscribe(WebSocketSession session, String docId) {
        Set<WebSocketSession> sessions = subscriptions.get(docId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                subscriptions.remove(docId);
            }
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
            .build().getQueryParams().getFirst(name);
    }
}
