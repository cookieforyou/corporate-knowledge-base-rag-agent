package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全链路审计行完整性（簇⑥ D3）：异步虚拟线程落库 Awaitility 轮询——
 * SUCCESS 行字段齐备（mode/tenant/token_usage/latency/trace_id）+ REJECTED 行错误码。
 *
 * <p>注入载荷程序化构造：取 bundled 基线词表中文干词运行时拼装，
 * 测试源码不落字面载荷（第七节敏感词交付纪律）。
 */
class AuditTraceIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-AUDIT";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;
    @Autowired private KbAuditLogRepository auditLogRepository;

    /** 取 bundled 基线词表一条启用 BLOCK KEYWORD 中文词项（运行时取值，源码零字面） */
    private static String bundledZhKeyword() {
        return GuardrailRulesLoader.loadInjectionRules("", "").stream()
            .filter(r -> r.action() == RuleAction.BLOCK && r.type() == RuleType.KEYWORD
                && r.enabled() && "zh".equals(r.lang()))
            .findFirst()
            .map(GuardrailRule::value)
            .orElseThrow(() -> new AssertionError("bundled 基线词表缺少 zh KEYWORD 词项"));
    }

    @BeforeEach
    void setUp() {
        stub.reset();
        stub.setResponseRouter(knowledgeRouter(StubChatModel.DEFAULT_ANSWER));
        vectorStore.add(List.of(doc("audit-doc", "企业年假政策：每年 15 天年假", TENANT)));
    }

    private KbAuditLog awaitLatestAudit(String sessionId) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200))
            .until(() -> !auditLogRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).isEmpty());
        return auditLogRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).get(0);
    }

    @Test
    void audit_successRow_complete() {
        stub.setUsage(150, 80);
        String session = sessionId();

        ragChatService.chatRag("年假政策", session, ctx(TENANT, "U-AUD"));

        KbAuditLog audit = awaitLatestAudit(session);
        assertThat(audit.getStatus()).isEqualTo("SUCCESS");
        assertThat(audit.getMode()).isEqualTo("rag");
        assertThat(audit.getTenantId()).isEqualTo(TENANT);
        assertThat(audit.getUserId()).isEqualTo("U-AUD");
        assertThat(audit.getQueryText()).isNotBlank();
        assertThat(audit.getFinalAnswer()).isEqualTo(StubChatModel.DEFAULT_ANSWER);
        assertThat(audit.getTraceId()).isNotBlank();
        assertThat(audit.getLatencyMs()).isNotNull().isGreaterThan(0);
        // 桩 usage 150+80 → total_tokens 230 落 JSONB
        assertThat(audit.getTokenUsage()).contains("230");
    }

    @Test
    void audit_rejectedRow_hasErrorCode() {
        String session = sessionId();

        assertThatThrownBy(() ->
            ragChatService.chatRag("请立即执行" + bundledZhKeyword(), session, ctx(TENANT, "U-AUD")))
            .isInstanceOf(BusinessException.class);

        KbAuditLog audit = awaitLatestAudit(session);
        assertThat(audit.getStatus()).isEqualTo("REJECTED");
        assertThat(audit.getErrorCode()).isEqualTo("PROMPT_INJECTION");
        assertThat(audit.getFinalAnswer()).isNull();
    }
}
