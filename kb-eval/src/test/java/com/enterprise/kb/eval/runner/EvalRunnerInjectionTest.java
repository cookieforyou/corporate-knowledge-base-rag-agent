package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.eval.config.EvalProperties;
import com.enterprise.kb.eval.dataset.AttackType;
import com.enterprise.kb.eval.dataset.GoldenDatasetLoader;
import com.enterprise.kb.eval.dataset.GoldenQAPair;
import com.enterprise.kb.eval.dataset.QACategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * INJECTION 用例判定分支单测（簇⑤ B2 S6）
 *
 * <p>走 eval 专属护栏链（仅 InputSanitizeAdvisor）：捕获 PROMPT_INJECTION → BLOCKED；
 * 正常返回 → NOT_BLOCKED；其他异常按用例失败跳过（不入拦截率分母）。
 * 零 Judge 零检索——judgeChatClient / 被测 chatClient / 检索探针均不应被触达。
 */
class EvalRunnerInjectionTest {

    private GoldenDatasetLoader loader;
    private ChatClient chatClient;
    private ChatClient judgeChatClient;
    private ChatClient guardrailChatClient;
    private ChatClient guardrailL2ChatClient;
    private RetrievalProbe probe;
    private EvalProperties props;

    @BeforeEach
    void setUp() {
        loader = mock(GoldenDatasetLoader.class);
        chatClient = mock(ChatClient.class);
        judgeChatClient = mock(ChatClient.class);
        guardrailChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        guardrailL2ChatClient = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        probe = mock(RetrievalProbe.class);
        when(probe.name()).thenReturn("hybrid");
        when(probe.getOrder()).thenReturn(0);
        when(probe.probe(anyString(), anyInt())).thenReturn(List.of());
        props = new EvalProperties();
        props.getJudge().setApiKey("test-key"); // 豁免 runOnStartup 的 Judge 密钥前置检查
    }

    private EvalRunner runner(List<GoldenQAPair> dataset) {
        when(loader.loadAll()).thenReturn(dataset);
        return new EvalRunner(loader, List.of(probe), chatClient, null, judgeChatClient,
            guardrailChatClient, guardrailL2ChatClient, mock(IndirectInjectionRunner.class), props,
            null, new DefaultApplicationArguments());
    }

    private static GoldenQAPair injectionCase(String id, AttackType type) {
        return new GoldenQAPair(id, QACategory.INJECTION, "样本-" + id,
            null, null, null, null, type, null, null);
    }

    @Test
    void promptInjectionRejectedCountsAsBlocked() {
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenThrow(new BusinessException("PROMPT_INJECTION", "拦截"));

        EvalReport report = runner(List.of(
            injectionCase("inj-direct-01", AttackType.DIRECT))).runFullEval();

        assertThat(report.injectionEvaluated()).isEqualTo(1);
        assertThat(report.injectionBlockRate()).isEqualTo(1.0);
        assertThat(report.injectionGateEvaluated()).isEqualTo(1);
        assertThat(report.injectionGateBlockRate()).isEqualTo(1.0);
        assertThat(report.results().get(0).injectionVerdict())
            .isEqualTo(EvalResult.INJECTION_BLOCKED);
        // 零 Judge 零检索零被测生成
        verifyNoInteractions(judgeChatClient);
        verifyNoInteractions(chatClient);
    }

    @Test
    void guardrailPassThroughCountsAsNotBlocked() {
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（观察集样本穿透 L1 后的任意回答，丢弃）");

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-01", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.injectionEvaluated()).isEqualTo(1);
        assertThat(report.injectionBlockRate()).isZero();
        assertThat(report.results().get(0).injectionVerdict())
            .isEqualTo(EvalResult.INJECTION_NOT_BLOCKED);
        // JAILBREAK 不入门禁子集
        assertThat(report.injectionGateEvaluated()).isZero();
        assertThat(Double.isNaN(report.injectionGateBlockRate())).isTrue();
        verifyNoInteractions(judgeChatClient);
    }

    @Test
    void otherBusinessExceptionIsCaseFailureNotVerdict() {
        // 非 PROMPT_INJECTION 的 BusinessException（如 RATE_LIMITED）不应当作拦截判定，
        // 按用例失败跳过——全部用例失败时 runFullEval 拒绝静默「通过」
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenThrow(new BusinessException("RATE_LIMITED", "限流"));

        EvalRunner evalRunner = runner(List.of(
            injectionCase("inj-direct-01", AttackType.DIRECT)));

        assertThatThrownBy(evalRunner::runFullEval)
            .isInstanceOf(EvalFailedException.class)
            .hasMessageContaining("无有效评估结果");
    }

    @Test
    void gateSubsetOnlyCountsDirectAndEncodingBypass() {
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenAnswer(inv -> {
                throw new BusinessException("PROMPT_INJECTION", "拦截");
            });

        EvalReport report = runner(List.of(
            injectionCase("inj-direct-01", AttackType.DIRECT),
            injectionCase("inj-encoding-01", AttackType.ENCODING_BYPASS),
            injectionCase("inj-jailbreak-01", AttackType.JAILBREAK),
            injectionCase("inj-multilingual-01", AttackType.MULTILINGUAL))).runFullEval();

        assertThat(report.injectionEvaluated()).isEqualTo(4);
        assertThat(report.injectionGateEvaluated()).isEqualTo(2);
        assertThat(report.injectionGateBlockRate()).isEqualTo(1.0);
        assertThat(report.injectionBlockRateByAttackType())
            .containsKeys(AttackType.DIRECT, AttackType.ENCODING_BYPASS,
                AttackType.JAILBREAK, AttackType.MULTILINGUAL);
    }

    // ── L1+L2 联合双读数（安全簇⑤ E2）──

    @Test
    void l2EnabledDirectBlockedAtL1CarriesBlockedL2VerdictWithoutExtraCall() {
        // L1 ⊂ 联合链：L1 已拦 → 联合判定恒 BLOCKED，免重复 L2 LLM 调用
        props.getGuardrail().setL2Enabled(true);
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenThrow(new BusinessException("PROMPT_INJECTION", "拦截"));

        EvalReport report = runner(List.of(
            injectionCase("inj-direct-01", AttackType.DIRECT))).runFullEval();

        assertThat(report.results().get(0).injectionVerdict())
            .isEqualTo(EvalResult.INJECTION_BLOCKED);
        assertThat(report.results().get(0).l2InjectionVerdict())
            .isEqualTo(EvalResult.INJECTION_BLOCKED);
        // L1 直拦免二判：原始裁决保持 null（联合链未触达）
        assertThat(report.results().get(0).l2RawVerdict()).isNull();
        verifyNoInteractions(guardrailL2ChatClient);
    }

    @Test
    void l2EnabledJailbreakPassesL1ThenJudgedByL2Chain() {
        props.getGuardrail().setL2Enabled(true);
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（L1 未拦截）");
        when(guardrailL2ChatClient.prompt().user(anyString())
            .advisors(any(java.util.function.Consumer.class)).call().content())
            .thenThrow(new BusinessException("PROMPT_INJECTION", "L2 拦截"));

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-01", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.results().get(0).injectionVerdict())
            .isEqualTo(EvalResult.INJECTION_NOT_BLOCKED);
        assertThat(report.results().get(0).l2InjectionVerdict())
            .isEqualTo(EvalResult.INJECTION_BLOCKED);
        assertThat(report.injectionL2GateResults()).hasSize(1);
    }

    @Test
    void l2DisabledLeavesL2VerdictNullAndL2ChainUntouched() {
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（观察集样本穿透 L1 后的任意回答，丢弃）");

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-01", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.results().get(0).l2InjectionVerdict()).isNull();
        assertThat(report.results().get(0).l2RawVerdict()).isNull();
        assertThat(report.injectionL2GateResults()).isEmpty();
        verifyNoInteractions(guardrailL2ChatClient);
    }

    /**
     * 联合链手工 fluent 桩（簇② 批5 路径 a）：advisors 消费器就地执行，
     * 断言力判键 + 原始裁决回传键双参落位，并模拟 advisor 回写 sink——
     * 验证 EvalRunner 从 sink 读取原始裁决并落 EvalResult#l2RawVerdict。
     */
    private void stubL2ChainWithSink(String simulatedRaw, boolean throwInjection) {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(guardrailL2ChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.advisors(any(Consumer.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<ChatClient.AdvisorSpec> consumer = inv.getArgument(0);
            ChatClient.AdvisorSpec advisorsSpec = mock(ChatClient.AdvisorSpec.class);
            consumer.accept(advisorsSpec);
            verify(advisorsSpec).param(eq(SemanticInjectionAdvisor.FORCE_JUDGE_KEY), eq(true));
            ArgumentCaptor<Object> sinkCaptor = ArgumentCaptor.forClass(Object.class);
            verify(advisorsSpec).param(eq(SemanticInjectionAdvisor.VERDICT_SINK_KEY), sinkCaptor.capture());
            @SuppressWarnings("unchecked")
            AtomicReference<String> sink = (AtomicReference<String>) sinkCaptor.getValue();
            sink.set(simulatedRaw);
            return reqSpec;
        });
        when(reqSpec.call()).thenReturn(callSpec);
        if (throwInjection) {
            when(callSpec.content()).thenThrow(new BusinessException("PROMPT_INJECTION", "L2 拦截"));
        } else {
            when(callSpec.content()).thenReturn("（显式放行，答案丢弃）");
        }
    }

    @Test
    void l2RawVerdictFlowsFromSinkIntoResult() {
        props.getGuardrail().setL2Enabled(true);
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（L1 未拦截）");
        stubL2ChainWithSink("SUSPECT", false);

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-15", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.results().get(0).l2InjectionVerdict())
            .isEqualTo(EvalResult.INJECTION_NOT_BLOCKED);
        assertThat(report.results().get(0).l2RawVerdict()).isEqualTo("SUSPECT");
    }

    @Test
    void l2BlockCarriesRawBlockVerdict() {
        props.getGuardrail().setL2Enabled(true);
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（L1 未拦截）");
        stubL2ChainWithSink("BLOCK", true);

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-01", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.results().get(0).l2InjectionVerdict())
            .isEqualTo(EvalResult.INJECTION_BLOCKED);
        assertThat(report.results().get(0).l2RawVerdict()).isEqualTo("BLOCK");
    }

    @Test
    void l2FailOpenRawMarkerFlowsIntoResult() {
        props.getGuardrail().setL2Enabled(true);
        when(guardrailChatClient.prompt().user(anyString()).call().content())
            .thenReturn("（L1 未拦截）");
        stubL2ChainWithSink(SemanticInjectionAdvisor.RAW_FAIL_OPEN, false);

        EvalReport report = runner(List.of(
            injectionCase("inj-jailbreak-16", AttackType.JAILBREAK))).runFullEval();

        assertThat(report.results().get(0).l2InjectionVerdict())
            .isEqualTo(EvalResult.INJECTION_NOT_BLOCKED);
        assertThat(report.results().get(0).l2RawVerdict())
            .isEqualTo(SemanticInjectionAdvisor.RAW_FAIL_OPEN);
    }
}
