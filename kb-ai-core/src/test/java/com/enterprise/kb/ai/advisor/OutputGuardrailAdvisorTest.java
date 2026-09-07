package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.guardrail.PromptCanary;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 输出安全护栏测试（3.6）—— 同步 after() 拦截 + 流式聚合后验 + 护栏命中计数（簇⑤ B2 S3）
 *
 * <p>v2.41/T2：词表切结构化加载（双源合并），测试词表经 CSV 兼容源注入占位词；
 * bundled 基线输出词表随 jar 发布并在构造时并入（不影响占位词断言语义）。
 *
 * <p>v2.42/T5：分类化替换（family 驱动安全话术 + 指标子项）与系统提示金丝雀
 * （运行时随机 token，动态断言不硬编码）；金丝雀关闭形态（PromptCanary(false)）
 * 保持既有断言语义。
 */
class OutputGuardrailAdvisorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutputGuardrailAdvisor advisor =
        new OutputGuardrailAdvisor("", "competitor_x,competitor_y",
            new AiBusinessMetrics(meterRegistry), new PromptCanary(false), PiiRecognizerRegistry.defaults());
    private final AdvisorChain chain = mock(AdvisorChain.class);

    private ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
            .context(Map.of("trace_start_ms", 1L))
            .build();
    }

    /** 带 RetrievalContext 的响应（T7：同步 after() 经 response.context() 取 ctx） */
    private ChatClientResponse responseWithCtx(String text, RetrievalContext ctx) {
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx))
            .build();
    }

    // ── 同步路径 after() ──

    @Test
    void blacklistedOutputReplacedWithContextPreserved() {
        ChatClientResponse result = advisor.after(response("推荐使用 competitor_x 的产品"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(result.context()).containsEntry("trace_start_ms", 1L);
    }

    @Test
    void cleanOutputPassesThroughUnchanged() {
        ChatClientResponse original = response("增值税发票是……");

        assertThat(advisor.after(original, chain)).isSameAs(original);
    }

    @Test
    void emptyResponsePassesThroughSafely() {
        ChatClientResponse empty = ChatClientResponse.builder()
            .context(Map.of("trace_start_ms", 1L))
            .build();

        assertThat(advisor.after(empty, chain)).isSameAs(empty);
    }

    // ── 流式路径 adviseStream() 聚合后验 ──

    private Flux<ChatClientResponse> streamThrough(OutputGuardrailAdvisor target,
                                                   List<ChatClientResponse> chunks) {
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of());
        when(streamChain.nextStream(any())).thenReturn(Flux.fromIterable(chunks));
        return target.adviseStream(request, streamChain);
    }

    /** 带 RetrievalContext 的流式驱动（T7：adviseStream 经 request 取 ctx） */
    private Flux<ChatClientResponse> streamThroughWithCtx(OutputGuardrailAdvisor target,
                                                          List<ChatClientResponse> chunks,
                                                          RetrievalContext ctx) {
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of(RetrievalContext.CONTEXT_KEY, ctx));
        when(streamChain.nextStream(any())).thenReturn(Flux.fromIterable(chunks));
        return target.adviseStream(request, streamChain);
    }

    @Test
    void cleanStreamReEmitsAllChunksInOrder() {
        List<ChatClientResponse> chunks = List.of(response("增值"), response("税发"), response("票"));

        List<String> texts = streamThrough(advisor, chunks)
            .map(r -> r.chatResponse().getResult().getOutput().getText())
            .collectList()
            .block();

        assertThat(texts).containsExactly("增值", "税发", "票");
    }

    @Test
    void violationSplitAcrossChunksReplacedBySingleSafeResponse() {
        // 敏感词跨块拆分——逐块检查必然漏检，聚合后验才能命中
        List<ChatClientResponse> chunks = List.of(response("推荐 compet"), response("itor_x 产品"));

        List<ChatClientResponse> results = streamThrough(advisor, chunks).collectList().block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
    }

    @Test
    void blankCsvCompatStillPassesUnrelatedOutput() {
        // CSV 空 → bundled 基线输出词表（T5 起为空，内容待 T4 带外注入）；无关输出照常放行
        OutputGuardrailAdvisor open = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), new PromptCanary(false), PiiRecognizerRegistry.defaults());
        ChatClientResponse original = response("competitor_x");

        assertThat(open.after(original, chain)).isSameAs(original);
    }

    // ── T5 分类化替换：family 驱动安全话术 + 指标子项 ──

    private OutputGuardrailAdvisor familyAdvisor() {
        return new OutputGuardrailAdvisor("classpath:guardrail-test/output-family-rules.yml", "",
            new AiBusinessMetrics(meterRegistry), new PromptCanary(false), PiiRecognizerRegistry.defaults());
    }

    @Test
    void businessConfidentialHitReplacedWithClassifiedTextAndSubCounter() {
        OutputGuardrailAdvisor target = familyAdvisor();

        ChatClientResponse result = target.after(response("内部数据 familytest-confidential 明细"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，该内容涉及企业内部保密信息，无法提供。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.business_confidential").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.competitor_comparison").count())
            .isZero();
    }

    @Test
    void competitorComparisonHitReplacedWithClassifiedTextAndSubCounter() {
        OutputGuardrailAdvisor target = familyAdvisor();

        ChatClientResponse result = target.after(response("关于 familytest-competitor 的对比"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，我们无法提供竞品对比相关的倾向性信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.competitor_comparison").count())
            .isEqualTo(1.0);
    }

    @Test
    void unclassifiedCsvHitFallsBackToComplianceTextWithoutSubCounter() {
        // legacy CSV 词项族系 UNCLASSIFIED → 默认合规话术，不产生分类子项
        ChatClientResponse result = advisor.after(response("推荐使用 competitor_x 的产品"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.business_confidential").count())
            .isZero();
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.compliance_sensitive").count())
            .isZero();
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.competitor_comparison").count())
            .isZero();
    }

    @Test
    void flagOnlyOutputRulePassesWithoutReplacement() {
        OutputGuardrailAdvisor target = familyAdvisor();
        ChatClientResponse original = response("包含 familytest-flagtoken 的输出");

        assertThat(target.after(original, chain)).isSameAs(original);
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isZero();
        // T7：无 ctx 形态仍计数（非 Web 入口只计数不落标记）
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "output", "family", "COMPLIANCE_SENSITIVE").count()).isEqualTo(1.0);
    }

    @Test
    void flagOnlySyncHitCountsAndWritesCtxMarkViaResponseContext() {
        // T7：同步 after() 经 response.context() 取 ctx（终端 ChatModelCallAdvisor Map.copyOf 实证）
        OutputGuardrailAdvisor target = familyAdvisor();
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        ChatClientResponse original = responseWithCtx("包含 familytest-flagtoken 的输出", ctx);

        assertThat(target.after(original, chain)).isSameAs(original);
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "output", "family", "COMPLIANCE_SENSITIVE").count()).isEqualTo(1.0);
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("output", "COMPLIANCE_SENSITIVE"));
    }

    @Test
    void flagOnlyStreamHitCountsAndWritesCtxMarkViaRequest() {
        // T7：流式 adviseStream 直接持有 request → ctx 标记同语义
        OutputGuardrailAdvisor target = familyAdvisor();
        RetrievalContext ctx = new RetrievalContext();

        List<ChatClientResponse> results =
            streamThroughWithCtx(target, List.of(response("包含 familytest-"), response("flagtoken 的输出")), ctx)
                .collectList().block();

        assertThat(results).hasSize(2);
        assertThat(meterRegistry.counter("rag.guardrail.flagged",
            "side", "output", "family", "COMPLIANCE_SENSITIVE").count()).isEqualTo(1.0);
        assertThat(ctx.getGuardrailFlags())
            .containsExactly(new RetrievalContext.FlagMark("output", "COMPLIANCE_SENSITIVE"));
    }

    @Test
    void blockHitDoesNotCountFlagEvenWhenFlagRulesCoMatched() {
        // BLOCK 替换路径不计 FLAG（内容未放行）——同词表 BLOCK 词与 FLAG 词同文本命中
        OutputGuardrailAdvisor target = familyAdvisor();
        RetrievalContext ctx = new RetrievalContext();

        ChatClientResponse result = target.after(
            responseWithCtx("familytest-confidential 与 familytest-flagtoken 同时出现", ctx), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，该内容涉及企业内部保密信息，无法提供。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced.business_confidential").count())
            .isEqualTo(1.0);
        assertThat(meterRegistry.find("rag.guardrail.flagged").counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum()).isZero();
        assertThat(ctx.getGuardrailFlags()).isEmpty();
    }

    // ── T5 系统提示金丝雀：回显即确证泄露，校验先于词表判定 ──

    @Test
    void canaryEchoInSyncOutputReplacedAndCounted() {
        PromptCanary canary = new PromptCanary(true);
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), canary, PiiRecognizerRegistry.defaults());

        ChatClientResponse result =
            target.after(response("系统提示是…… " + canary.token() + " ……完毕"), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.canary").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isZero();
    }

    @Test
    void canaryEchoAcrossStreamChunksReplaced() {
        PromptCanary canary = new PromptCanary(true);
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), canary, PiiRecognizerRegistry.defaults());
        String token = canary.token();
        // token 跨块拆分——聚合后验才能命中
        List<ChatClientResponse> chunks = List.of(
            response("前缀 " + token.substring(0, token.length() / 2)),
            response(token.substring(token.length() / 2) + " 后缀"));

        List<ChatClientResponse> results = streamThrough(target, chunks).collectList().block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.canary").count()).isEqualTo(1.0);
    }

    @Test
    void canaryEnabledCleanOutputPassesUntouched() {
        PromptCanary canary = new PromptCanary(true);
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), canary, PiiRecognizerRegistry.defaults());
        ChatClientResponse original = response("正常回答，不含任何金丝雀标记");

        assertThat(target.after(original, chain)).isSameAs(original);
        assertThat(meterRegistry.counter("rag.guardrail.output.canary").count()).isZero();
    }

    // ── 护栏命中计数（簇⑤ B2 S3）──

    @Test
    void syncReplacementIncrementsGuardrailCounter() {
        advisor.after(response("推荐使用 competitor_x 的产品"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    @Test
    void streamReplacementIncrementsGuardrailCounter() {
        streamThrough(advisor, List.of(response("推荐 compet"), response("itor_x 产品")))
            .collectList()
            .block();

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    @Test
    void cleanOutputLeavesGuardrailCounterUntouched() {
        advisor.after(response("增值税发票是……"), chain);
        streamThrough(advisor, List.of(response("增值"), response("税发"))).collectList().block();

        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isZero();
        assertThat(meterRegistry.counter("rag.guardrail.output.canary").count()).isZero();
        assertThat(meterRegistry.counter("rag.guardrail.output.pii.echo").count()).isZero();
    }

    // ── PII 回显探测（安全簇③ C2，簇① T5 钩子闭环）：FLAG 观察起步只计数不替换 ──

    @Test
    void piiEchoInSyncOutputCountedButNotReplaced() {
        ChatClientResponse original = response("请拨打 13911112222 联系管理员");

        ChatClientResponse result = advisor.after(original, chain);

        // 观察语义：原样放行不替换 + rag.guardrail.output.pii.echo 计数
        assertThat(result.chatResponse().getResult().getOutput().getText())
            .contains("13911112222");
        assertThat(meterRegistry.counter("rag.guardrail.output.pii.echo").count()).isEqualTo(1.0);
    }

    @Test
    void piiEchoAcrossStreamChunksCountedOnce() {
        // 聚合后验：PII 跨块拆分仍检出，观察计数一次、块原样放行
        List<ChatClientResponse> results = streamThrough(advisor,
                List.of(response("服务器地址 192.168."), response("1.10 已就绪")))
            .collectList()
            .block();

        assertThat(results).hasSize(2);
        assertThat(meterRegistry.counter("rag.guardrail.output.pii.echo").count()).isEqualTo(1.0);
    }

    @Test
    void maskedPiiInOutputNotCountedAsEcho() {
        // 掩码形态不是回显（幂等纪律的对偶面：输入侧已脱敏的形态直通）
        advisor.after(response("联系电话 1***-****-**** 已脱敏"), chain);

        assertThat(meterRegistry.counter("rag.guardrail.output.pii.echo").count()).isZero();
    }

    // ── v2.109 增量放行（带 ctx；无 ctx / REGEX 轨回落聚合——上方既有流式用例即聚合语义）──

    /** 受控合成词表（窗口确定，剥离 bundled 基线词表的窗口影响）：competitor_x 12 字 → 窗口 11 */
    private OutputGuardrailAdvisor controlledAdvisor(PromptCanary canary) {
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), canary, PiiRecognizerRegistry.defaults());
        target.onOutputRulesUpdated(List.of(new GuardrailRule("kw-probe-01", "UNCLASSIFIED",
            "", RuleType.KEYWORD, "competitor_x", RuleAction.BLOCK, true, null)));
        return target;
    }

    /** 增量放行时序：块到达即放行（先于上游完成）——聚合形态首元素必在 complete 之后 */
    @Test
    void incrementalStreamReleasesChunksBeforeCompletion() throws Exception {
        OutputGuardrailAdvisor target = controlledAdvisor(new PromptCanary(false));
        RetrievalContext ctx = new RetrievalContext();
        Sinks.Many<ChatClientResponse> upstream = Sinks.many().unicast().onBackpressureBuffer();
        StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("q"))), Map.of(RetrievalContext.CONTEXT_KEY, ctx));
        when(streamChain.nextStream(any())).thenReturn(upstream.asFlux());

        CountDownLatch firstReleased = new CountDownLatch(1);
        target.adviseStream(request, streamChain)
            .doOnNext(r -> firstReleased.countDown())
            .subscribe();

        // 窗口 11：15 字块即放行 4 字切片
        upstream.tryEmitNext(response("前".repeat(15)));
        assertThat(firstReleased.await(2, TimeUnit.SECONDS))
            .as("块到达即放行（此刻上游尚未 complete）").isTrue();
        upstream.tryEmitComplete();

        assertThat(ctx.isOutputReplaced()).isFalse();
    }

    /** 跨块截断：命中词零流出 + 已放行前缀保留 + ctx 携带话术（REPLACE 消费点信号） */
    @Test
    void incrementalViolationTruncatesMarksCtxAndNeverReleasesHitWord() {
        OutputGuardrailAdvisor target = controlledAdvisor(new PromptCanary(false));
        RetrievalContext ctx = new RetrievalContext();

        List<ChatClientResponse> results = streamThroughWithCtx(target, List.of(
                response("前".repeat(15)),          // 窗口 11 → 放行 4 字
                response(" competitor_x 及后续文本")),  // 命中 → 吞块截断
            ctx).collectList().block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chatResponse().getResult().getOutput().getText())
            .isEqualTo("前".repeat(4));            // 不含命中词任何片段
        assertThat(ctx.isOutputReplaced()).isTrue();
        assertThat(ctx.getOutputReplacement()).isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    /** 金丝雀增量截断：token 计入窗口（token 永不出流）+ ctx 打标 + 独立指标 */
    @Test
    void incrementalCanaryEchoTruncatesAndMarks() {
        PromptCanary canary = new PromptCanary(true);
        // 窗口 = max(canary token 46 - 1, 11) = 45（kb-canary- 前缀 10 + UUID 36）
        OutputGuardrailAdvisor target = controlledAdvisor(canary);
        RetrievalContext ctx = new RetrievalContext();

        List<ChatClientResponse> results = streamThroughWithCtx(target, List.of(
                response("合".repeat(60)),            // 窗口 45 → 放行 15 字
                response(" " + canary.token() + " 尾部")),
            ctx).collectList().block();

        assertThat(results).hasSize(1);
        String released = results.get(0).chatResponse().getResult().getOutput().getText();
        assertThat(released).isEqualTo("合".repeat(15));
        assertThat(released).doesNotContain("kb-canary-");
        assertThat(ctx.isOutputReplaced()).isTrue();
        assertThat(ctx.getOutputReplacement()).isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.canary").count()).isEqualTo(1.0);
    }

    /**
     * REGEX 轨（无窗口语义）：模式跨已放行区与新增块（流中 pending 判定视图已被
     * 窗口裁剪，单块视图不命中）——流末 fullText 检出补救，已流出文本经 ctx 打标
     * 由 REPLACE 帧追回（泄露窗口 = 答案播放时长，分层检出语义）。
     */
    @Test
    void regexRuleCrossingReleasedTextDetectedAtStreamEnd() {
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), new PromptCanary(false), PiiRecognizerRegistry.defaults());
        // 纯 REGEX 词表替换 → 窗口 1（KEYWORD 空 + 金丝雀关）；模式跨「内部…机密」
        target.onOutputRulesUpdated(List.of(new GuardrailRule("rx-probe-01", "UNCLASSIFIED", "",
            RuleType.REGEX, "内部[\\s\\S]*机密", RuleAction.BLOCK, true,
            Pattern.compile("内部[\\s\\S]*机密"))));
        RetrievalContext ctx = new RetrievalContext();

        // 块1「内部说明」全量放行（窗口 1）；块2「机密42」单块视图无「内部」不命中、
        // 放行 4/5 字；流末 fullText「内部说明机密42」find 命中 → 追回
        List<ChatClientResponse> results = streamThroughWithCtx(target, List.of(
                response("内部说明"), response("机密42")), ctx)
            .collectList().block();

        String released = results.stream()
            .map(r -> r.chatResponse().getResult() == null ? "" :
                r.chatResponse().getResult().getOutput().getText())
            .collect(Collectors.joining());
        assertThat(released).contains("内部说明");     // 已放行部分确实流出（追回语义的泄露窗口面）
        assertThat(ctx.isOutputReplaced()).isTrue();   // 流末检出 → REPLACE 追回
        assertThat(ctx.getOutputReplacement()).isEqualTo("抱歉，由于合规要求，无法提供该信息。");
        assertThat(meterRegistry.counter("rag.guardrail.output.replaced").count()).isEqualTo(1.0);
    }

    /** 空文本块（usage/finishReason 元数据帧）无泄露面，原样透传（计量传播链不断） */
    @Test
    void incrementalEmptyTextMetadataChunkPassesThrough() {
        RetrievalContext ctx = new RetrievalContext();
        ChatClientResponse usageFrame = ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of()))
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx))
            .build();

        List<ChatClientResponse> results = streamThroughWithCtx(advisor, List.of(
                response("合规内容"), usageFrame), ctx)
            .collectList().block();

        assertThat(results).anySatisfy(r -> assertThat(r).isSameAs(usageFrame));
    }

    /**
     * 尾窗块元数据传播（E2E 回传热修）：审计流式取数读流<b>最后一个</b>元素的
     * model/usage——尾窗合成块（concatWith 段，恒为末元素）必须携带流末真实
     * metadata。曾以 recombined(null,…) 构造，缺省 ChatResponseMetadata 即
     * model "" + EmptyUsage 零值，致 kb_audit_log.model_name 空 +
     * token_usage 归零（三链路 SUCCESS 轮全中）。
     */
    @Test
    void tailFlushCarriesLastRealMetadataForAuditLastChunkRead() {
        OutputGuardrailAdvisor target = controlledAdvisor(new PromptCanary(false));
        RetrievalContext ctx = new RetrievalContext();
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .model("glm-test").usage(new DefaultUsage(10, 20, 30)).build();
        ChatClientResponse usageFrame = ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(), metadata))
            .context(Map.of(RetrievalContext.CONTEXT_KEY, ctx))
            .build();

        // 内容 6 字 < 窗口 11：流中零放行，尾窗整体 flush = 末元素
        List<ChatClientResponse> results = streamThroughWithCtx(target, List.of(
                response("合规内容若干"), usageFrame), ctx)
            .collectList().block();

        ChatClientResponse last = results.get(results.size() - 1);
        assertThat(last.chatResponse().getResult().getOutput().getText())
            .isEqualTo("合规内容若干");
        assertThat(last.chatResponse().getMetadata().getModel()).isEqualTo("glm-test");
        assertThat(last.chatResponse().getMetadata().getUsage().getTotalTokens()).isEqualTo(30);
    }

    // ── 热重载（安全簇⑥ F1）──

    @Test
    void hotReloadCallbackSwapsOutputRules() {
        OutputGuardrailAdvisor target = new OutputGuardrailAdvisor("", "",
            new AiBusinessMetrics(meterRegistry), new PromptCanary(false), PiiRecognizerRegistry.defaults());
        // 干净业务文本对 bundled 基线词表零命中（既有 clean 断言同词面）
        ChatClientResponse original = response("增值税发票是……");
        assertThat(target.after(original, chain)).isSameAs(original);

        // 热重载回调换入合成 BLOCK 词项（占位词干，无语义）→ 同输出即整段替换
        target.onOutputRulesUpdated(List.of(new GuardrailRule("reload-probe-01", "UNCLASSIFIED",
            "", RuleType.KEYWORD, "增值税发票", RuleAction.BLOCK, true, null)));

        ChatClientResponse replaced = target.after(original, chain);
        assertThat(replaced.chatResponse().getResult().getOutput().getText())
            .isEqualTo("抱歉，由于合规要求，无法提供该信息。");
    }
}
