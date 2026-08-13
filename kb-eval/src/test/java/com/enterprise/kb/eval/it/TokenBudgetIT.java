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
import org.redisson.api.RedissonClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token 预算 429（簇⑥ D3，CTX-QUOTA 上下文）：Redis 日账本超额拦截 +
 * 成功请求 usage 累加真实落账（流式计账同族机制，簇③ D1）。
 */
@TestPropertySource(properties = {
    "rag.ratelimit.tenant.rate=2",
    "rag.ratelimit.tenant.interval-seconds=60",
    "rag.token-budget.daily-limit=500"
})
class TokenBudgetIT extends AbstractAdvisorChainIT {

    private static final String KEY_PREFIX = "rag:token-budget:";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;
    @Autowired private KbAuditLogRepository auditLogRepository;
    @Autowired private RedissonClient redissonClient;

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        vectorStore.add(List.of(doc("budget-doc", "企业年假政策：每年 15 天年假", "T-BUDGET-SEED")));
    }

    /** kb_audit_log.tenant_id 为 varchar(36)，截断控长 */
    private String freshTenant() {
        return "T-BUDGET-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String ledgerKey(String tenant) {
        return KEY_PREFIX + tenant + ":" + LocalDate.now();
    }

    @Test
    void tokenBudget_exceeded_throws() {
        String tenant = freshTenant();
        String session = sessionId();
        redissonClient.getAtomicLong(ledgerKey(tenant)).set(500);   // 预写满额账本

        assertThatThrownBy(() -> ragChatService.chatRag("年假政策", session, ctx(tenant, "U-B")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("TOKEN_BUDGET_EXCEEDED");

        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
                List<KbAuditLog> logs = auditLogRepository.findBySessionIdOrderByCreatedAtDesc(session);
                assertThat(logs).isNotEmpty();
                KbAuditLog latest = logs.get(0);
                assertThat(latest.getStatus()).isEqualTo("REJECTED");
                assertThat(latest.getErrorCode()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
            });
    }

    @Test
    void tokenBudget_usageAccumulated() {
        String tenant = freshTenant();
        stub.setUsage(200, 100);

        ragChatService.chatRag("年假政策", sessionId(), ctx(tenant, "U-B"));

        // usage 200+100=300 落账（计账写入可能在响应路径末尾，轮询等待）
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .until(() -> redissonClient.getAtomicLong(ledgerKey(tenant)).get() == 300L);
    }
}
