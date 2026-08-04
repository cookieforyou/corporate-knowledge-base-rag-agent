package com.enterprise.kb.ai.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 容错会话记忆装饰器测试（3.1）
 *
 * <p>防回归：MessageChatMemoryAdvisor 对记忆读写失败无内部兜底，
 * 装饰器必须保证 Redis 故障不沿 Advisor 链击穿核心问答链路。
 */
class FaultTolerantChatMemoryTest {

    private final ChatMemory delegate = mock(ChatMemory.class);
    private final FaultTolerantChatMemory memory = new FaultTolerantChatMemory(delegate);

    @Test
    void delegatesNormalReadWrite() {
        List<Message> history = List.of(new UserMessage("上一轮问题"));
        when(delegate.get("s1")).thenReturn(history);

        assertThat(memory.get("s1")).isEqualTo(history);

        memory.add("s1", List.of(new UserMessage("新问题")));
        verify(delegate).add("s1", List.of(new UserMessage("新问题")));
    }

    @Test
    void readFailureDegradesToEmptyHistory() {
        when(delegate.get(anyString())).thenThrow(new RuntimeException("Redis 连接超时"));

        assertThat(memory.get("s1")).isEmpty();
    }

    @Test
    void writeFailureIsSwallowed() {
        doThrow(new RuntimeException("Redis 连接超时")).when(delegate).add(anyString(), anyList());

        assertThatCode(() -> memory.add("s1", List.of(new UserMessage("新问题"))))
            .doesNotThrowAnyException();
    }

    @Test
    void clearFailureIsSwallowed() {
        doThrow(new RuntimeException("Redis 连接超时")).when(delegate).clear(anyString());

        assertThatCode(() -> memory.clear("s1")).doesNotThrowAnyException();
    }
}
