package com.enterprise.kb.ai.agent.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * 钉钉 Stream 模式客户端（Phase5簇⑥ 5.12）——WebSocket 出站长连接收 @ 消息。
 *
 * <p><b>接入形态</b>（官方 SDK javap 核验 2026-09-13）：
 * {@code OpenDingTalkStreamClientBuilder.custom()} + Client ID/Secret 凭证 →
 * {@link DingTalkStreamTopics#BOT_MESSAGE_TOPIC} 注册
 * {@code OpenDingTalkCallbackListener<ChatbotMessage, Void>} →
 * {@link OpenDingTalkClient#start()}。SDK 自管重连与心跳。
 *
 * <p><b>SmartLifecycle 桥接</b>：SDK 客户端即 start/stop 对——容器启动后期建连
 * （其他 Bean 就绪后）、停机时断连（优雅下线）。连接建立失败抛异常即启动失败
 * （fail-fast：凭证错/网络不可达不静默裸跑）。
 *
 * <p><b>零入站端点</b>：出站长连接形态——SecurityConfig/nginx/ECS 安全组零改动
 * （相对 webhook 回调的决定性优势，实施方案 3.3 勘察定案）。
 */
@Slf4j
public class DingTalkStreamClient implements SmartLifecycle {

    private final DingTalkProperties properties;
    private final DingTalkChatService chatService;
    private volatile OpenDingTalkClient client;
    private volatile boolean running;

    public DingTalkStreamClient(DingTalkProperties properties, DingTalkChatService chatService) {
        this.properties = properties;
        this.chatService = chatService;
    }

    @Override
    public void start() {
        OpenDingTalkClient built = OpenDingTalkStreamClientBuilder.custom()
            .credential(new AuthClientCredential(properties.getClientId(), properties.getClientSecret()))
            .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC,
                (OpenDingTalkCallbackListener<ChatbotMessage, Void>) message -> {
                    chatService.handle(message);
                    // 应答经 sessionWebhook 异步推回，Stream 通道无同步应答载荷
                    return null;
                })
            .build();
        try {
            built.start();
        } catch (Exception e) {
            throw new IllegalStateException(
                "钉钉 Stream 连接建立失败（核对 Client ID/Secret 与网络出站）: " + e.getMessage(), e);
        }
        this.client = built;
        this.running = true;
        log.info("钉钉群机器人 Stream 客户端已启动（tenant={}）", properties.getTenantId());
    }

    @Override
    public void stop() {
        OpenDingTalkClient current = client;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception e) {
                log.warn("钉钉 Stream 客户端停机异常（忽略）: {}", e.getMessage());
            }
        }
        running = false;
        log.info("钉钉群机器人 Stream 客户端已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
