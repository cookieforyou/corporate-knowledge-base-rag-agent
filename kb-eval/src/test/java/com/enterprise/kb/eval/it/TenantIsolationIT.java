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
 * 双租户隔离（簇⑥ D3，呼应 3.9/3.10 安全收敛）：FilterExpression 租户过滤真实生效 +
 * 无租户 fail-closed 空检索。
 */
class TenantIsolationIT extends AbstractAdvisorChainIT {

    private static final String TENANT_A = "T-ISO-A";
    private static final String TENANT_B = "T-ISO-B";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        stub.reset();
        vectorStore.add(List.of(
            doc("iso-a1", "企业年假政策：每年 15 天年假", TENANT_A),
            doc("iso-a2", "报销流程规范：先审批再提交", TENANT_A),
            doc("iso-b1", "机密收购计划：整体方案与时间表", TENANT_B)));
    }

    @Test
    void tenantA_onlySeesOwnDocuments() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        RetrievalContext ctx = ctx(TENANT_A, "U-A");

        ragChatService.chatRag("年假政策", sessionId(), ctx);

        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("15 天年假").doesNotContain("收购计划");
        // 溯源帧内证据全部属于租户 A
        ctx.getTraceSummary().stream()
            .flatMap(e -> e.documents().stream())
            .forEach(d -> assertThat(d.getMetadata().get("tenant_id")).isEqualTo(TENANT_A));
    }

    @Test
    void tenantB_onlySeesOwnDocuments() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        RetrievalContext ctx = ctx(TENANT_B, "U-B");

        ragChatService.chatRag("收购计划", sessionId(), ctx);

        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("收购计划").doesNotContain("年假政策");
    }

    @Test
    void noTenant_failClosed_emptyRetrieval() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        // 有 ctx 无租户：检索器双路零触达返回空（fail-closed 第二层，3.9/3.10）
        RetrievalContext ctx = new RetrievalContext();
        ctx.setUserId("U-ANON");

        ragChatService.chatRag("年假政策", sessionId(), ctx);

        String finalPrompt = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(finalPrompt).contains("知识库中未检索到");
    }
}
