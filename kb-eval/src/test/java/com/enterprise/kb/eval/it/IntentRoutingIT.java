package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 意图路由 L1/L2（簇⑥ D3，5.4 收窄版）：L1 正则闲聊零 LLM 短路 + L2 桩分类
 * CHITCHAT 短路 + KNOWLEDGE 全管线。skipRetrieval 经 RetrievalContext 参数链回传。
 */
class IntentRoutingIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-INTENT";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        stub.reset();
        vectorStore.add(List.of(doc("intent-doc", "企业年假政策：每年 15 天年假", TENANT)));
    }

    @Test
    void l1_chitchat_skipRetrieval() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        RetrievalContext ctx = ctx(TENANT, "U-I");

        String answer = ragChatService.chatRag("你好", sessionId(), ctx);

        assertThat(answer).isEqualTo(StubChatModel.DEFAULT_ANSWER);
        assertThat(ctx.isSkipRetrieval()).isTrue();
        assertThat(ctx.getTraceSummary()).isEmpty();
        // L1 零 LLM：仅一次最终调用（无分类调用），且 prompt 无 grounding 参考资料
        assertThat(stub.userTexts).hasSize(1);
        assertThat(stub.userTexts.get(0)).isEqualTo("你好");
    }

    @Test
    void l1_thanks_skipRetrieval() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        RetrievalContext ctx = ctx(TENANT, "U-I");

        ragChatService.chatRag("谢谢", sessionId(), ctx);

        assertThat(ctx.isSkipRetrieval()).isTrue();
        assertThat(ctx.getTraceSummary()).isEmpty();
    }

    @Test
    void l2_metaChitchat_skipRetrieval() {
        // 对话元问题不在 L1 词表 → L2 分类器判 CHITCHAT（桩按消息内容路由）
        stub.setResponseRouter(userText -> {
            if (userText != null && userText.contains("意图分类器")) {
                if (userText.contains("我刚才问了什么")) {
                    return "{\"intent\":\"CHITCHAT\",\"rewrittenQuery\":null}";
                }
                return "{\"intent\":\"KNOWLEDGE\",\"rewrittenQuery\":\"" + "其他" + "\"}";
            }
            return StubChatModel.DEFAULT_ANSWER;
        });
        RetrievalContext ctx = ctx(TENANT, "U-I");

        ragChatService.chatRag("你还记得我刚才问了什么吗", sessionId(), ctx);

        assertThat(ctx.isSkipRetrieval()).isTrue();
        assertThat(ctx.getTraceSummary()).isEmpty();
    }

    @Test
    void knowledgeQuery_fullPipeline() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        RetrievalContext ctx = ctx(TENANT, "U-I");

        ragChatService.chatRag("公司年假政策是什么", sessionId(), ctx);

        assertThat(ctx.isSkipRetrieval()).isFalse();
        assertThat(ctx.getTraceSummary()).isNotEmpty();
        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("15 天年假").contains("[ref-1]");
    }
}
