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
 * 知识问答接地 Happy Path（簇⑥ D3）：证据注入 [ref-N] 锚定 + 溯源帧 + 空证据拒答。
 */
class RagHappyPathIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-HAPPY";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        stub.reset();
    }

    @Test
    void knowledgeQuestion_answerGrounded() {
        vectorStore.add(List.of(doc("hp-ground", "公司差旅报销流程：先审批再报销，上限 5000 元", TENANT)));
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));

        RetrievalContext ctx = ctx(TENANT, "U-HAPPY");
        String answer = ragChatService.chatRag("报销上限是多少", sessionId(), ctx);

        assertThat(answer).isEqualTo(StubChatModel.DEFAULT_ANSWER);
        assertThat(ctx.isSkipRetrieval()).isFalse();
        // 末次模型调用的 user 消息 = grounding 模板注入后的最终 prompt：证据原文 + [ref-N] 编号
        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("公司差旅报销流程").contains("[ref-1]");
    }

    @Test
    void retrievalTrace_populated() {
        vectorStore.add(List.of(doc("hp-trace", "企业考勤制度：弹性上班时间为九点半", TENANT)));
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));

        RetrievalContext ctx = ctx(TENANT, "U-HAPPY");
        ragChatService.chatRag("考勤弹性上班时间", sessionId(), ctx);

        // 参数链回传：溯源帧非空且证据文档非空（SSE TRACE 的 final 通道同源）
        assertThat(ctx.getTraceSummary()).isNotEmpty();
        assertThat(ctx.getTraceSummary().stream()
            .flatMap(e -> e.documents().stream())
            .count()).isPositive();
    }

    @Test
    void emptyEvidence_rejected() {
        // 零共享词正交向量（hashing trick 相似度 0 < 阈值 0.1）→ 检索空 → 拒答模板
        vectorStore.add(List.of(doc("hp-unrelated", "员工年假与节日福利政策汇编", TENANT)));
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));

        RetrievalContext ctx = ctx(TENANT, "U-HAPPY");
        String answer = ragChatService.chatRag("量子物理原理", sessionId(), ctx);

        // allowEmptyContext=false：模型收到空证据拒答模板而非原问题（桩不遵循指令，回答照发）
        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("知识库中未检索到").contains("禁止依据自身知识作答");
        assertThat(answer).isEqualTo(StubChatModel.DEFAULT_ANSWER);
    }
}
