package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨租户泄露集成（簇⑥ D3，3.10 安全收敛回归保险）：同题双租户各自只见本方数据 +
 * 审计租户归属正确 + 向量库 FilterExpression 直测。
 */
class CrossTenantLeakIT extends AbstractAdvisorChainIT {

    private static final String TENANT_A = "T-LEAK-A";
    private static final String TENANT_B = "T-LEAK-B";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;
    @Autowired private KbAuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        stub.reset();
        vectorStore.add(List.of(
            doc("leak-a", "财务报表摘要：去年营收 1 亿", TENANT_A),
            doc("leak-b", "财务报表摘要：去年营收 5 千万", TENANT_B)));
    }

    @Test
    void crossTenant_noLeak_sameQuestion() {
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));

        String sessionA = sessionId();
        ragChatService.chatRag("财务报表营收", sessionA, ctx(TENANT_A, "U-A"));
        String promptA = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(promptA).contains("1 亿").doesNotContain("5 千万");

        String sessionB = sessionId();
        ragChatService.chatRag("财务报表营收", sessionB, ctx(TENANT_B, "U-B"));
        String promptB = stub.userTexts.get(stub.userTexts.size() - 1);
        assertThat(promptB).contains("5 千万").doesNotContain("1 亿");

        // 审计租户归属（异步落库轮询）
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                List<KbAuditLog> logsA = auditLogRepository.findBySessionIdOrderByCreatedAtDesc(sessionA);
                assertThat(logsA).isNotEmpty();
                assertThat(logsA.get(0).getTenantId()).isEqualTo(TENANT_A);
                List<KbAuditLog> logsB = auditLogRepository.findBySessionIdOrderByCreatedAtDesc(sessionB);
                assertThat(logsB).isNotEmpty();
                assertThat(logsB.get(0).getTenantId()).isEqualTo(TENANT_B);
            });
    }

    @Test
    void crossTenant_vectorFilterEnforced() {
        List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("财务报表")
                .topK(10)
                .filterExpression("tenant_id == 'T-LEAK-A' AND is_deleted == false")
                .build());

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(
            d -> assertThat(d.getMetadata().get("tenant_id")).isEqualTo(TENANT_A));
    }
}
