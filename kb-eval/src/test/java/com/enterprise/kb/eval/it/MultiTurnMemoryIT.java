package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多轮记忆（簇⑥ D3）：RedisChatMemoryRepository 真读写——轮间历史注入 +
 * 会话隔离 + 窗口内消息数。
 */
class MultiTurnMemoryIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-MEMORY";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;
    @Autowired private ChatMemory agentChatMemory;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        vectorStore.add(List.of(doc("memory-doc", "企业年假政策：每年 15 天年假", TENANT)));
    }

    @Test
    void multiTurn_contextRetained() {
        String session = sessionId();
        RetrievalContext ctx = ctx(TENANT, "U-M");

        ragChatService.chatRag("年假多少天", session, ctx);
        ragChatService.chatRag("那病假呢", session, ctx);

        // Redis 真持久化：两轮共 4 条消息（2 user + 2 assistant）
        assertThat(agentChatMemory.get(session)).hasSize(4);

        // 第 2 轮最终模型调用的消息列表含第 1 轮问答（记忆 Advisor 注入历史）
        List<String> lastSnapshot = stub.promptSnapshots.get(stub.promptSnapshots.size() - 1);
        String joined = String.join("\n", lastSnapshot);
        assertThat(joined).contains("年假多少天").contains(StubChatModel.DEFAULT_ANSWER);
    }

    @Test
    void multiTurn_differentSessions_isolated() {
        String session1 = sessionId();
        String session2 = sessionId();
        RetrievalContext ctx = ctx(TENANT, "U-M");

        ragChatService.chatRag("年假多少天", session1, ctx);
        ragChatService.chatRag("报销流程", session2, ctx);

        assertThat(agentChatMemory.get(session1)).hasSize(2);
        assertThat(agentChatMemory.get(session2)).hasSize(2);
        String session2Text = agentChatMemory.get(session2).stream()
            .map(m -> m.getText() == null ? "" : m.getText())
            .reduce("", (a, b) -> a + b);
        assertThat(session2Text).doesNotContain("年假多少天");
    }
}
