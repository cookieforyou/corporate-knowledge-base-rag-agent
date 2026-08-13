package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户限流 429（簇⑥ D3，CTX-QUOTA 上下文）：Redisson 令牌桶真实限流 +
 * 租户独立桶 + 被拒审计行。
 */
@TestPropertySource(properties = {
    "rag.ratelimit.tenant.rate=2",
    "rag.ratelimit.tenant.interval-seconds=60",
    "rag.token-budget.daily-limit=500"
})
class RateLimitIT extends AbstractAdvisorChainIT {

    private static final String TENANT_PREFIX = "T-RATE-";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;
    @Autowired private KbAuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        vectorStore.add(List.of(doc("rate-doc", "企业年假政策：每年 15 天年假", "T-RATE-SEED")));
    }

    /** 每用例全新租户 = 全新令牌桶，用例间零清理；kb_audit_log.tenant_id 为 varchar(36)，截断控长 */
    private String freshTenant() {
        return TENANT_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    @Test
    void rateLimit_exceeded_throws429() {
        String tenant = freshTenant();
        String session = sessionId();
        RetrievalContext ctx = ctx(tenant, "U-R");

        assertThat(ragChatService.chatRag("年假政策", session, ctx)).isEqualTo(StubChatModel.DEFAULT_ANSWER);
        assertThat(ragChatService.chatRag("年假政策", session, ctx)).isEqualTo(StubChatModel.DEFAULT_ANSWER);

        assertThatThrownBy(() -> ragChatService.chatRag("年假政策", session, ctx))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("RATE_LIMITED");

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                List<KbAuditLog> logs = auditLogRepository.findBySessionIdOrderByCreatedAtDesc(session);
                assertThat(logs).isNotEmpty();
                KbAuditLog latest = logs.get(0);
                assertThat(latest.getStatus()).isEqualTo("REJECTED");
                assertThat(latest.getErrorCode()).isEqualTo("RATE_LIMITED");
            });
    }

    @Test
    void rateLimit_differentTenant_independent() {
        String tenantA = freshTenant();
        String tenantB = freshTenant();

        // 租户 A 耗尽配额（2 次）
        ragChatService.chatRag("年假政策", sessionId(), ctx(tenantA, "U-R"));
        ragChatService.chatRag("年假政策", sessionId(), ctx(tenantA, "U-R"));
        assertThatThrownBy(() -> ragChatService.chatRag("年假政策", sessionId(), ctx(tenantA, "U-R")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("RATE_LIMITED");

        // 租户 B 独立桶不受影响
        assertThat(ragChatService.chatRag("年假政策", sessionId(), ctx(tenantB, "U-R")))
            .isEqualTo(StubChatModel.DEFAULT_ANSWER);
    }
}
