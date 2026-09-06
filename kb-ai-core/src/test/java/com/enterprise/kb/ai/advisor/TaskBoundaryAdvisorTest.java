package com.enterprise.kb.ai.advisor;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 任务边界 Advisor 契约测试（簇⑤ 收官注记① 三轮，11 章 v2.108）——消息层
 * 结构分隔注记：历史在场插入 / 首轮透传 / 注记为 SystemMessage 类型
 * （不进记忆回写——MessageChatMemoryAdvisor 的 user 写入取
 * getLastUserOrToolResponseMessage，SystemMessage 不被选中）。
 */
class TaskBoundaryAdvisorTest {

    private static ChatClientRequest request(List<Message> messages) {
        return new ChatClientRequest(new Prompt(messages), new HashMap<>());
    }

    /** 历史在场（UserMessage > 1）：分隔注记插在最后一条用户消息前，SystemMessage 类型 */
    @Test
    void insertsBoundaryNoteBeforeCurrentUserWhenHistoryPresent() {
        ChatClientRequest advised = TaskBoundaryAdvisor.applyBoundary(request(List.of(
            new UserMessage("第一轮任务"),
            new AssistantMessage("第一轮回答"),
            new UserMessage("第二轮任务"))));

        List<Message> messages = advised.prompt().getInstructions();
        assertThat(messages).hasSize(4);

        int noteIdx = -1;
        int lastUserIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) noteIdx = i;
            if (messages.get(i) instanceof UserMessage) lastUserIdx = i;
        }
        assertThat(noteIdx).isEqualTo(lastUserIdx - 1);
        assertThat(((SystemMessage) messages.get(noteIdx)).getText())
            .contains("历史轮次到此结束")
            .contains("均已交付完结")
            .contains("仅处理下一条用户消息所述任务");
    }

    /** 首轮（单 UserMessage）：透传同一实例，消息序列零变化 */
    @Test
    void firstRoundPassesThroughUnchanged() {
        ChatClientRequest request = request(List.of(
            new SystemMessage("system prompt"),
            new UserMessage("首轮任务")));

        ChatClientRequest advised = TaskBoundaryAdvisor.applyBoundary(request);

        assertSame(request, advised);
        assertThat(advised.prompt().getInstructions()).hasSize(2);
    }

    /** adviseCall：注入完成后透传 chain（下游收到含注记的请求） */
    @Test
    void adviseCallInjectsBeforeDelegating() {
        TaskBoundaryAdvisor advisor = new TaskBoundaryAdvisor();
        AtomicReference<ChatClientRequest> seen = new AtomicReference<>();
        CallAdvisorChain chain = new CallAdvisorChain() {
            @Override
            public ChatClientResponse nextCall(ChatClientRequest request) {
                seen.set(request);
                return null;
            }

            @Override
            public java.util.List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() {
                return List.of();
            }

            @Override
            public CallAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) {
                throw new UnsupportedOperationException();
            }
        };

        advisor.adviseCall(request(List.of(
            new UserMessage("旧任务"), new AssistantMessage("旧回答"), new UserMessage("新任务"))), chain);

        assertThat(seen.get().prompt().getInstructions())
            .anySatisfy(m -> assertThat(m.getText()).contains("历史轮次到此结束"));
    }

    /** adviseStream：流式路径同形注入 */
    @Test
    void adviseStreamInjectsBeforeDelegating() {
        TaskBoundaryAdvisor advisor = new TaskBoundaryAdvisor();
        AtomicReference<ChatClientRequest> seen = new AtomicReference<>();
        StreamAdvisorChain chain = new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                seen.set(request);
                return Flux.empty();
            }

            @Override
            public java.util.List<org.springframework.ai.chat.client.advisor.api.StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }

            @Override
            public StreamAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.StreamAdvisor advisor) {
                throw new UnsupportedOperationException();
            }
        };

        advisor.adviseStream(request(List.of(
            new UserMessage("旧任务"), new AssistantMessage("旧回答"), new UserMessage("新任务"))), chain)
            .collectList().block();

        assertThat(seen.get().prompt().getInstructions())
            .anySatisfy(m -> assertThat(m.getText()).contains("历史轮次到此结束"));
    }
}
