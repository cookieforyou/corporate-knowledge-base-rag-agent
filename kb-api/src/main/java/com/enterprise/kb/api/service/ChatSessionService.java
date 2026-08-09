package com.enterprise.kb.api.service;

import com.enterprise.kb.api.dto.AgentStreamEvent;
import com.enterprise.kb.api.dto.AgentStreamEvent.SourceTrace;
import com.enterprise.kb.api.dto.HistoryMessageItem;
import com.enterprise.kb.api.dto.SessionItem;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbFeedback;
import com.enterprise.kb.domain.model.KbMessage;
import com.enterprise.kb.domain.model.KbSession;
import com.enterprise.kb.domain.repository.KbFeedbackRepository;
import com.enterprise.kb.domain.repository.KbMessageRepository;
import com.enterprise.kb.domain.repository.KbSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * 会话与消息 PG 归档 + 历史会话服务（3.1 归档；3.15 补齐历史列表/溯源恢复/续聊回填）
 *
 * <p>与 Redis 热记忆的分工：Redis 承载对话窗口（MessageWindowChatMemory，TTL 24h，
 * 供记忆 Advisor 即时读写）；PG 为事实源旁路归档——kb_feedback 外键、
 * 前端历史会话列表（3.15）、审计留痕均依赖消息落 PG（3.1 复审发现的清单缺口）。
 *
 * <p>归档为增值数据，与主链路失败隔离：虚拟线程异步执行 + 全程 try/catch，
 * PG 抖动只丢归档不丢对话（与 ETL INDEXING 不阻断同策）。
 *
 * <p><b>溯源归档（v2.17）</b>：assistant 消息 citations 列写入 SSE TRACE 同形
 * 载荷（TraceEvent JSON），历史会话打开即可恢复溯源面板与 [ref-N] 对齐；
 * metadata 记 traceId（反馈回填审计行凭据）。tool 链/闲聊免检索轮无溯源 → null。
 *
 * <p><b>记忆回填（v2.17）</b>：Redis 记忆 TTL 24h，过期会话续聊前经
 * {@link #reseedMemoryIfAbsent} 以 PG 消息重建窗口，实现「真续聊」。
 */
@Slf4j
@Service
public class ChatSessionService {

    /** 会话标题取首轮问题前 N 字符（历史列表可读性） */
    private static final int TITLE_MAX_LENGTH = 50;
    /** 回填窗口与 rag.chat.memory.max-messages 默认值同规（≈10 轮） */
    private static final int RESEED_WINDOW = 20;
    /** 回填单发守卫键前缀与 TTL：只覆盖 check-then-act 竞态窗口，过期后允许再次回填 */
    private static final String RESEED_GUARD_PREFIX = "rag:session-reseed:";
    private static final long RESEED_GUARD_TTL_SECONDS = 30;
    /** 会话列表分页上限 */
    private static final int MAX_PAGE_SIZE = 100;

    private final KbSessionRepository sessionRepository;
    private final KbMessageRepository messageRepository;
    private final KbFeedbackRepository feedbackRepository;
    private final ChatMemory agentChatMemory;
    private final RedissonClient redissonClient;
    private final JsonMapper jsonMapper;

    public ChatSessionService(KbSessionRepository sessionRepository,
                              KbMessageRepository messageRepository,
                              KbFeedbackRepository feedbackRepository,
                              @Qualifier("agentChatMemory") ChatMemory agentChatMemory,
                              RedissonClient redissonClient,
                              JsonMapper jsonMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.agentChatMemory = agentChatMemory;
        this.redissonClient = redissonClient;
        this.jsonMapper = jsonMapper;
    }

    // ── 归档（3.1 + v2.17 溯源落库）──

    /**
     * 异步归档一轮对话（用户消息 + 助手回复），会话不存在则创建。
     *
     * <p>由 Controller 在同步调用返回后 / SSE 流完成后调用；@Async 移交
     * sessionArchiveExecutor（虚拟线程），调用方线程零等待。
     *
     * <p><b>助手消息 ID 前移（3.17）</b>：assistantMessageId 由 Controller 请求线程
     * 预生成，经 SSE DONE 帧/同步响应送达前端作为反馈 API 的定位键，归档复用同一
     * ID 保证 kb_feedback.message_id 外键可解析；缺省回落自生成（兼容既有调用形态）。
     *
     * <p><b>溯源载荷（v2.17）</b>：traceEvent 非空时序列化为 citations JSON
     * （与 SSE TRACE 帧同形）；traceId 非空时写入 metadata。序列化失败降级
     * null（溯源是旁路增值数据，不击穿归档）。
     */
    @Async("sessionArchiveExecutor")
    public void archiveTurn(String sessionId, String tenantId, String userId,
                            String query, String answer, String assistantMessageId,
                            AgentStreamEvent.TraceEvent traceEvent, String traceId) {
        try {
            ensureSession(sessionId, tenantId, userId, query);
            messageRepository.save(newMessage(sessionId, "USER", query, null, null, null));
            messageRepository.save(newMessage(sessionId, "ASSISTANT", answer, assistantMessageId,
                serializeTrace(traceEvent), metadataOf(traceId)));
            sessionRepository.incrementMessageCount(sessionId, 2);
        } catch (Exception e) {
            log.warn("会话归档失败（不影响对话）: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    /** TraceEvent → citations JSON；null 或序列化失败 → null（降级，warn 留痕） */
    private String serializeTrace(AgentStreamEvent.TraceEvent traceEvent) {
        if (traceEvent == null) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(traceEvent);
        } catch (Exception e) {
            log.warn("溯源载荷序列化失败，citations 降级为空: {}", e.getMessage());
            return null;
        }
    }

    /** metadata JSON：仅 traceId 非空时写入（Spring AI Document metadata 禁 null 同款纪律） */
    private String metadataOf(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(Map.of("traceId", traceId));
        } catch (Exception e) {
            log.warn("消息 metadata 序列化失败，降级为空: {}", e.getMessage());
            return null;
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

    private static KbMessage newMessage(String sessionId, String role, String content,
                                        String id, String citations, String metadata) {
        KbMessage message = new KbMessage();
        message.setId(id != null && !id.isBlank() ? id : UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCitations(citations);
        message.setMetadata(metadata);
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

    // ── 记忆回填（v2.17 历史会话真续聊）──

    /**
     * Redis 记忆为空且 PG 有该会话消息时，回填最近 {@value #RESEED_WINDOW} 条入记忆窗口。
     *
     * <p>由 chat 同步/流式两入口在 Advisor 链执行前调用：过期会话（Redis TTL 24h）
     * 续聊时 MessageChatMemoryAdvisor 读到回填后的完整窗口。全程 fail-open——
     * 任何异常仅告警，最坏退化为现状（无历史上下文）。
     *
     * <p>SETNX 短 TTL 单发守卫防并发双回填（check-then-act 竞态致历史重复入窗）。
     */
    public void reseedMemoryIfAbsent(String sessionId) {
        try {
            boolean acquired = redissonClient.<String>getBucket(RESEED_GUARD_PREFIX + sessionId)
                .trySet("1", RESEED_GUARD_TTL_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                return; // 并发回填进行中
            }
            if (!agentChatMemory.get(sessionId).isEmpty()) {
                return; // 热会话零开销路径
            }
            List<KbMessage> recent = messageRepository.findTop20BySessionIdOrderByCreatedAtDesc(sessionId);
            if (recent.isEmpty()) {
                return; // 真·新会话
            }
            List<Message> messages = new ArrayList<>();
            for (int i = recent.size() - 1; i >= 0; i--) { // 反转为时间升序
                KbMessage m = recent.get(i);
                if (m.getContent() == null || m.getContent().isBlank()) {
                    continue;
                }
                switch (m.getRole()) {
                    case "USER" -> messages.add(new UserMessage(m.getContent()));
                    case "ASSISTANT" -> messages.add(new AssistantMessage(m.getContent()));
                    default -> log.warn("回填跳过未知角色消息: sessionId={}, role={}", sessionId, m.getRole());
                }
            }
            if (!messages.isEmpty()) {
                agentChatMemory.add(sessionId, messages);
                log.info("历史会话记忆回填: sessionId={}, 条数={}", sessionId, messages.size());
            }
        } catch (Exception e) {
            log.warn("记忆回填失败（不影响本轮问答）: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    // ── 历史会话查询/删除（3.15 补齐）──

    /** 会话列表：tenant+user 双过滤，updated_at 倒序；size 上限 {@value #MAX_PAGE_SIZE}，page 负值归零 */
    public List<SessionItem> listSessions(String tenantId, String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return sessionRepository
            .findByTenantIdAndUserIdOrderByUpdatedAtDesc(tenantId, userId, PageRequest.of(safePage, safeSize))
            .map(s -> new SessionItem(s.getId(), s.getTitle(), s.getMessageCount(), s.getUpdatedAt()))
            .getContent();
    }

    /**
     * 历史消息：归属校验 fail-closed（不存在/跨租户/跨用户一律 SESSION_NOT_FOUND，
     * 不泄露存在性，与 FeedbackService MESSAGE_NOT_FOUND 同纪律）；assistant 消息附
     * citations 解析后的溯源载荷、traceId 与当前用户既有反馈评价。
     */
    public List<HistoryMessageItem> loadMessages(String sessionId, String tenantId, String userId) {
        KbSession session = requireOwnedSession(sessionId, tenantId, userId);
        List<KbMessage> messages = messageRepository.findBySessionIdOrderByCreatedAt(session.getId());
        Map<String, KbFeedback> feedbackByMessage = feedbackOf(messages, userId);
        return messages.stream()
            .map(m -> toItem(m, feedbackByMessage.get(m.getId())))
            .toList();
    }

    /**
     * 删除会话：归属校验同上；先清反馈（kb_feedback.message_id 外键无级联，
     * 不清则删消息外键违例）→ 再删会话（kb_message.session_id ON DELETE CASCADE
     * 级联清消息）；顺带清 Redis 记忆（旁路容错）。反馈与会话同事务，避免半删态。
     */
    @Transactional
    public void deleteSession(String sessionId, String tenantId, String userId) {
        KbSession session = requireOwnedSession(sessionId, tenantId, userId);
        feedbackRepository.deleteBySessionId(session.getId());
        sessionRepository.deleteById(session.getId());
        try {
            agentChatMemory.clear(sessionId);
        } catch (Exception e) {
            log.warn("删除会话后清理 Redis 记忆失败（不影响删除结果）: sessionId={}, {}", sessionId, e.getMessage());
        }
    }

    /** 归属校验 fail-closed：会话不存在或非本租户/用户 → SESSION_NOT_FOUND */
    private KbSession requireOwnedSession(String sessionId, String tenantId, String userId) {
        KbSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException("SESSION_NOT_FOUND", "会话不存在或无权访问"));
        if (!Objects.equals(session.getTenantId(), tenantId)
            || !Objects.equals(session.getUserId(), userId)) {
            throw new BusinessException("SESSION_NOT_FOUND", "会话不存在或无权访问");
        }
        return session;
    }

    /** 批量查当前用户对 assistant 消息的既有评价（upsert 语义下至多每消息一条） */
    private Map<String, KbFeedback> feedbackOf(List<KbMessage> messages, String userId) {
        List<String> assistantIds = messages.stream()
            .filter(m -> "ASSISTANT".equals(m.getRole()))
            .map(KbMessage::getId)
            .toList();
        if (assistantIds.isEmpty()) {
            return Map.of();
        }
        return feedbackRepository.findByMessageIdInAndUserId(assistantIds, userId).stream()
            .collect(Collectors.toMap(KbFeedback::getMessageId, Function.identity(), (a, b) -> b));
    }

    private HistoryMessageItem toItem(KbMessage m, KbFeedback feedback) {
        boolean assistant = "ASSISTANT".equals(m.getRole());
        return new HistoryMessageItem(
            m.getId(),
            m.getRole(),
            m.getContent(),
            m.getCreatedAt(),
            assistant ? parseSources(m.getCitations()) : null,
            assistant ? traceIdOf(m.getMetadata()) : null,
            feedback != null && feedback.getRating() != null ? feedback.getRating().name() : null);
    }

    /** citations JSON → TRACE 同形溯源结构；null/解析失败 → null（降级同旧数据形态） */
    private List<SourceTrace> parseSources(String citations) {
        if (citations == null || citations.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(citations, AgentStreamEvent.TraceEvent.class).sources();
        } catch (Exception e) {
            log.warn("citations 解析失败，溯源降级为空: {}", e.getMessage());
            return null;
        }
    }

    /** metadata JSON 提取 traceId；缺失/解析失败 → null */
    @SuppressWarnings("unchecked")
    private String traceIdOf(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = jsonMapper.readValue(metadata, Map.class);
            Object traceId = map.get("traceId");
            return traceId == null ? null : traceId.toString();
        } catch (Exception e) {
            log.warn("消息 metadata 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
