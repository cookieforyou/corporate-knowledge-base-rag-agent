package com.enterprise.kb.api.service;

import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 会话与消息 PG 归档（3.1，设计文档第七章 kb_session/kb_message）
 *
 * <p>与 Redis 热记忆的分工：Redis 承载对话窗口（MessageWindowChatMemory，TTL 24h，
 * 供记忆 Advisor 即时读写）；PG 为事实源旁路归档——kb_feedback 外键、
 * 前端历史会话列表（3.15）、审计留痕均依赖消息落 PG（3.1 复审发现的清单缺口）。
 *
 * <p>归档为增值数据，与主链路失败隔离：虚拟线程异步执行 + 全程 try/catch，
 * PG 抖动只丢归档不丢对话（与 ETL INDEXING 不阻断同策）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 会话标题取首轮问题前 N 字符（历史列表可读性） */
    private static final int TITLE_MAX_LENGTH = 50;

    private final KbSessionRepository sessionRepository;
    private final KbMessageRepository messageRepository;

    /**
     * 异步归档一轮对话（用户消息 + 助手回复），会话不存在则创建。
     *
     * <p>由 Controller 在同步调用返回后 / SSE 流完成后调用；@Async 移交
     * sessionArchiveExecutor（虚拟线程），调用方线程零等待。
     */
    @Async("sessionArchiveExecutor")
    public void archiveTurn(String sessionId, String tenantId, String userId,
                            String query, String answer) {
        try {
            ensureSession(sessionId, tenantId, userId, query);
            messageRepository.save(newMessage(sessionId, "USER", query));
            messageRepository.save(newMessage(sessionId, "ASSISTANT", answer));
            sessionRepository.incrementMessageCount(sessionId, 2);
        } catch (Exception e) {
            log.warn("会话归档失败（不影响对话）: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    /** 会话不存在则创建；并发首建的主键冲突容忍为「已存在」 */
    private void ensureSession(String sessionId, String tenantId, String userId, String firstQuery) {
        if (sessionRepository.existsById(sessionId)) {
            return;
        }
        KbSession session = new KbSession();
        session.setId(sessionId);
        // 表约束 NOT NULL：身份缺失兜底占位（生产链路 JWT 保证非空，此为防御）
        session.setTenantId(tenantId != null ? tenantId : "unknown");
        session.setUserId(userId != null ? userId : "unknown");
        session.setTitle(titleOf(firstQuery));
        try {
            sessionRepository.save(session);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发首建竞争：对方已写入，本次归档继续即可
            log.debug("会话已由并发请求创建: {}", sessionId);
        }
    }

    private static KbMessage newMessage(String sessionId, String role, String content) {
        KbMessage message = new KbMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static String titleOf(String firstQuery) {
        if (firstQuery == null || firstQuery.isBlank()) {
            return "未命名会话";
        }
        String trimmed = firstQuery.trim();
        return trimmed.length() <= TITLE_MAX_LENGTH
            ? trimmed
            : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
    }
}
