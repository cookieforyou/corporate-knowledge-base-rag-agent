package com.enterprise.kb.ai.agent.dingtalk;

import com.dingtalk.open.app.api.chatbot.BotReplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 钉钉会话回复客户端（Phase5簇⑥ 5.12）——SDK {@link BotReplier} 薄封装。
 *
 * <p>回复通道 = 消息推送体自带的 {@code sessionWebhook}（每消息一次性、
 * 有过期时间）：直接 POST markdown 消息到该地址即完成群内回复——无需
 * 机器人主动发消息 API 与额外权限面。
 *
 * <p>独立成类（而非服务内直调）便于单测桩接（Mockito mock 本类即可）。
 */
@Slf4j
@Component
public class DingTalkReplyClient {

    /**
     * 经会话 webhook 回复 markdown 消息。
     *
     * @param sessionWebhook 消息推送体自带的一次性回复地址
     * @param title          卡片标题（问题摘要）
     * @param text           markdown 正文（答案 + 溯源附录）
     * @throws IllegalStateException 回复失败（网络/地址过期）——由调用方计错不重试
     *                              （消息已处理，不可重放语义清楚）
     */
    public void replyMarkdown(String sessionWebhook, String title, String text) {
        try {
            String response = BotReplier.fromWebhook(sessionWebhook).replyMarkdown(title, text);
            log.debug("钉钉回复完成: sessionWebhookDigest={}, response={}",
                digestOf(sessionWebhook), digestOf(response));
        } catch (IOException e) {
            throw new IllegalStateException("钉钉回复失败: " + e.getMessage(), e);
        }
    }

    private static String digestOf(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }
}
