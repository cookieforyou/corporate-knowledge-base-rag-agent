package com.enterprise.kb.ai.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 容错会话记忆装饰器（3.1）—— 记忆后端故障不击穿核心问答链路
 *
 * <p>MessageChatMemoryAdvisor 对记忆的读/写失败无内部兜底：Redis 抖动会沿
 * Advisor 链直接上抛为对话 500。本项目对增值数据的一贯策略是「失败降级、
 * 主链路不受牵连」（检索单路降级 10.2 / rerank 降级 10.5 / ETL INDEXING
 * 不阻断 9.x），记忆同为增值数据，故以装饰器统一兜底：
 * <ul>
 *   <li>读失败 → 视为空历史（本轮退化为单轮，下一轮恢复）</li>
 *   <li>写失败 → 丢弃本轮记忆（PG 侧 kb_session/kb_message 归档为独立旁路，
 *       审计与历史列表不受影响）</li>
 * </ul>
 *
 * <p>clear 失败仅告警——清理属运维动作，无主链路影响。
 */
@Slf4j
public class FaultTolerantChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public FaultTolerantChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        try {
            delegate.add(conversationId, messages);
        } catch (Exception e) {
            log.warn("会话记忆写入失败，丢弃本轮记忆（conversation={}）: {}",
                conversationId, e.getMessage());
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        try {
            return delegate.get(conversationId);
        } catch (Exception e) {
            log.warn("会话记忆读取失败，本轮退化为单轮（conversation={}）: {}",
                conversationId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void clear(String conversationId) {
        try {
            delegate.clear(conversationId);
        } catch (Exception e) {
            log.warn("会话记忆清理失败（conversation={}）: {}", conversationId, e.getMessage());
        }
    }
}
