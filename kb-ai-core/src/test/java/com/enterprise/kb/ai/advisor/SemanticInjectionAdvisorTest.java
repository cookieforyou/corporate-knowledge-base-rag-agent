package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L2 语义判定护栏测试（安全簇⑤ E1）—— 触发真值表 / 三裁决 / fail-open / 跨轮信号
 *
 * <p>触发侧断言经测试词表（guardrail-test/l2-trigger-rules.yml，REGEX 轨 + KEYWORD
 * 双型占位词，无攻击语义）程序化构造；裁决侧经深桩 ChatClient 返回结构化 verdict。
 * 零字面载荷（第七节敏感词交付纪律）。
 */
class SemanticInjectionAdvisorTest {

    private static final String TEST_RULES = "classpath:guardrail-test/l2-trigger-rules.yml";

    /** REGEX 轨占位词命中形态（词表 value 运行时取值构造，源码零字面） */
    private static final String REGEX_HIT_TEXT = "hello l2regextest-abc";
    /** KEYWORD 占位词命中形态 */
    private static final String KEYWORD_HIT_TEXT = "text with l2keywordtest inside";
    private static final String CLEAN_TEXT = "什么是增值税发票？";

    private ChatClient chatClient;
    private ChatMemory chatMemory;
    private SimpleMeterRegistry registry;
    private AiBusinessMetrics metrics;
    private final AdvisorChain chain = mock(AdvisorChain.class);

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        chatMemory = mock(ChatMemory.class);
        registry = new SimpleMeterRegistry();
        metrics = new AiBusinessMetrics(registry);
    }

    private SemanticInjectionAdvisor advisor(boolean enabled) {
        return advisor(enabled, 3);
    }

    private SemanticInjectionAdvisor advisor(boolean enabled, int timeoutSeconds) {
        return new SemanticInjectionAdvisor(
            GuardrailRulesLoader.loadInjectionRules(TEST_RULES, ""),
            metrics, chatClient, chatMemory, enabled, timeoutSeconds, 6);
    }

    private ChatClientRequest request(String userText) {
        return request(userText, Map.of(ChatMemory.CONVERSATION_ID, "sess-1"));
    }

    private ChatClientRequest request(String userText, Map<String, Object> extraContext) {
        Map<String, Object> context = new HashMap<>(extraContext);
        return new ChatClientRequest(new Prompt(List.of(new UserMessage(userText))), context);
    }

    private void stubVerdict(L2Verdict verdict) {
        when(chatClient.prompt().user(anyString()).call()
            .entity(eq(L2Verdict.class))).thenReturn(verdict);
    }

    private double counter(String name) {
        return registry.counter(name).count();
    }

    // ── 触发真值表（REGEX 命中 × 干词命中组合）──

    @Test
    void regexHitWithoutKeywordTriggersL2() {
        stubVerdict(new L2Verdict("PASS", null));

        ChatClientRequest result = advisor(true).before(request(REGEX_HIT_TEXT), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.triggered")).isEqualTo(1.0);
    }

    @Test
    void keywordHitSuppressesTriggerEvenWithRegexHit() {
        // FLAG 干词命中视为 L1 已观察，不触发 L2（对齐设计「干词未命中」）
        ChatClientRequest original = request(REGEX_HIT_TEXT + " " + KEYWORD_HIT_TEXT);

        ChatClientRequest result = advisor(true).before(original, chain);

        assertThat(result).isSameAs(original);
        assertThat(counter("rag.guardrail.l2.triggered")).isZero();
        verifyNoInteractions(chatClient);
    }

    @Test
    void keywordOnlyHitDoesNotTrigger() {
        ChatClientRequest original = request(KEYWORD_HIT_TEXT);

        ChatClientRequest result = advisor(true).before(original, chain);

        assertThat(result).isSameAs(original);
        verifyNoInteractions(chatClient);
    }

    @Test
    void cleanTextDoesNotTrigger() {
        ChatClientRequest original = request(CLEAN_TEXT);

        ChatClientRequest result = advisor(true).before(original, chain);

        assertThat(result).isSameAs(original);
        assertThat(counter("rag.guardrail.l2.triggered")).isZero();
        verifyNoInteractions(chatClient);
    }

    @Test
    void forceJudgeKeyTriggersRegardlessOfRules() {
        // 力判直通：eval 联合链专用 context 键，无视触发启发式
        stubVerdict(new L2Verdict("PASS", null));

        advisor(true).before(request(CLEAN_TEXT,
            Map.of(SemanticInjectionAdvisor.FORCE_JUDGE_KEY, true)), chain);

        assertThat(counter("rag.guardrail.l2.triggered")).isEqualTo(1.0);
    }

    @Test
    void crossTurnRegexSignalTriggersOnCleanCurrentMessage() {
        // 多阶段注入简单规则：当前消息未触发，近 N 条记忆拼接视图 REGEX 命中即触发
        when(chatMemory.get("sess-1")).thenReturn(List.of(new UserMessage("prev " + REGEX_HIT_TEXT)));
        stubVerdict(new L2Verdict("PASS", null));

        advisor(true).before(request(CLEAN_TEXT), chain);

        assertThat(counter("rag.guardrail.l2.triggered")).isEqualTo(1.0);
    }

    @Test
    void disabledAdvisorNeverTriggers() {
        ChatClientRequest original = request(REGEX_HIT_TEXT);

        ChatClientRequest result = advisor(false).before(original, chain);

        assertThat(result).isSameAs(original);
        verifyNoInteractions(chatClient);
    }

    @Test
    void nullJudgeClientPassesThrough() {
        // 备用模型未装配（fail-open 构造性保证）：恒 pass 不触达任何判定
        SemanticInjectionAdvisor noModel = new SemanticInjectionAdvisor(
            GuardrailRulesLoader.loadInjectionRules(TEST_RULES, ""),
            metrics, null, chatMemory, true, 3, 6);

        ChatClientRequest original = request(REGEX_HIT_TEXT);
        assertThat(noModel.before(original, chain)).isSameAs(original);
        assertThat(counter("rag.guardrail.l2.triggered")).isZero();
    }

    // ── 三裁决分流 ──

    @Test
    void blockVerdictRejectsWithPromptInjection() {
        stubVerdict(new L2Verdict("BLOCK", "INSTRUCTION_OVERRIDE"));

        assertThatThrownBy(() -> advisor(true).before(request(REGEX_HIT_TEXT), chain))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("PROMPT_INJECTION");
        assertThat(counter("rag.guardrail.l2.blocked")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.l2.triggered")).isEqualTo(1.0);
    }

    @Test
    void suspectVerdictFlagsAndPasses() {
        // SUSPECT → FLAG 计数放行：rag.guardrail.flagged(side=input,family) + ctx 审计标记
        stubVerdict(new L2Verdict("SUSPECT", "ROLE_HIJACK"));
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");

        ChatClientRequest result = advisor(true).before(
            request(REGEX_HIT_TEXT, Map.of(RetrievalContext.CONTEXT_KEY, ctx)), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.suspect")).isEqualTo(1.0);
        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "input", "family", "ROLE_HIJACK").count()).isEqualTo(1.0);
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("input", "ROLE_HIJACK"));
    }

    @Test
    void suspectVerdictWithNonEnumFamilyFallsBackToUnclassified() {
        // 簇⑤ E2E 实证：模型可能返回中文族名而非枚举名 → 归一 UNCLASSIFIED 兜底
        // （与 AiBusinessMetrics 预注册标签域对齐，观察族系不漂移；指标侧无基数风险）
        stubVerdict(new L2Verdict("SUSPECT", "越狱引导族"));
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");

        ChatClientRequest result = advisor(true).before(
            request(REGEX_HIT_TEXT, Map.of(RetrievalContext.CONTEXT_KEY, ctx)), chain);

        assertThat(result).isNotNull();
        assertThat(registry.counter("rag.guardrail.flagged",
            "side", "input", "family", "UNCLASSIFIED").count()).isEqualTo(1.0);
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("input", "UNCLASSIFIED"));
    }

    @Test
    void suspectVerdictFamilyIsCaseTolerant() {
        stubVerdict(new L2Verdict("SUSPECT", "jailbreak"));
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");

        ChatClientRequest result = advisor(true).before(
            request(REGEX_HIT_TEXT, Map.of(RetrievalContext.CONTEXT_KEY, ctx)), chain);

        assertThat(result).isNotNull();
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("input", "JAILBREAK"));
    }

    @Test
    void passVerdictLeavesCountersUntouched() {
        stubVerdict(new L2Verdict("PASS", null));

        ChatClientRequest result = advisor(true).before(request(REGEX_HIT_TEXT), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.triggered")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.l2.blocked")).isZero();
        assertThat(counter("rag.guardrail.l2.suspect")).isZero();
    }

    @Test
    void verdictComparisonIsCaseInsensitive() {
        stubVerdict(new L2Verdict("block", null));

        assertThatThrownBy(() -> advisor(true).before(request(REGEX_HIT_TEXT), chain))
            .isInstanceOf(BusinessException.class);
    }

    // ── fail-open 纪律 ──

    @Test
    void llmExceptionFailsOpenWithL1Conclusion() {
        when(chatClient.prompt().user(anyString()).call()
            .entity(eq(L2Verdict.class))).thenThrow(new RuntimeException("model down"));

        ChatClientRequest result = advisor(true).before(request(REGEX_HIT_TEXT), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.error")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.l2.blocked")).isZero();
    }

    @Test
    void timeoutFailsOpenWithL1Conclusion() {
        // 二判超过 timeout-seconds → Future.get 超时取消，fail-open 回落 L1 结论
        when(chatClient.prompt().user(anyString()).call()
            .entity(eq(L2Verdict.class))).thenAnswer(inv -> {
                Thread.sleep(2500);
                return new L2Verdict("BLOCK", null);
            });

        ChatClientRequest result = advisor(true, 1).before(request(REGEX_HIT_TEXT), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.error")).isEqualTo(1.0);
        assertThat(counter("rag.guardrail.l2.blocked")).isZero();
    }

    @Test
    void nullVerdictFailsOpenWithoutBlock() {
        stubVerdict(null);

        ChatClientRequest result = advisor(true).before(request(REGEX_HIT_TEXT), chain);

        assertThat(result).isNotNull();
        assertThat(counter("rag.guardrail.l2.blocked")).isZero();
    }

    // ── 热重载（安全簇⑥ F1）──

    @Test
    void hotReloadCallbackSwapsTriggerRules() {
        SemanticInjectionAdvisor target = advisor(true);
        // 热重载回调换入空词表 → 原 REGEX 命中文本不再触发二判
        target.onInjectionRulesUpdated(List.of());

        ChatClientRequest original = request(REGEX_HIT_TEXT);

        assertThat(target.before(original, chain)).isSameAs(original);
        assertThat(counter("rag.guardrail.l2.triggered")).isZero();
        verifyNoInteractions(chatClient);
    }
}
