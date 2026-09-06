package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.guardrail.PromptCanary;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesListener;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.GuardrailRulesRegistry;
import com.enterprise.kb.commons.guardrail.OutputFamily;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.guardrail.RuleType;
import com.enterprise.kb.commons.security.TextSanitizer;
import com.enterprise.kb.commons.security.pii.PiiHit;
import com.enterprise.kb.commons.security.pii.PiiRecognizerRegistry;
import com.enterprise.kb.commons.security.pii.PiiType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 输出安全护栏（设计文档 12.2，任务 3.6）—— 敏感词拦截替换 + 系统提示金丝雀
 *
 * <p>Order 110：after() 在内层 Advisor（记忆/检索）之后执行，审查最终输出。
 *
 * <p><b>流式语义（v2.109 双形态）</b>：BaseAdvisor 默认 adviseStream 仅对
 * onFinishReason 末块执行 after()——违规 token 此前已逐个流出，无法追回。
 * 3.6 形态为<b>聚合后验</b>（整流缓冲后判定，合规内容到达时刻全部后移为「生成
 * 完毕瞬间倾泻」——三链路流式体验归零，v2.109 改造动因）。现缺省走<b>增量放行</b>
 * （逐块判定 + 尾部保留窗 + 命中吞块截断 + ctx 打标追回，REGEX 轨流末检出，
 * 详见 adviseStream 注）；ctx 缺席时保守回落聚合形态（零泄露）。同步路径（/chat）经
 * 默认 adviseCall + after() 全量拦截不变。
 *
 * <p>L1 形态（12.2.1）：黑名单规则链。幻觉拦截（引用忠实性）归评估体系
 * （16.2 Citation Attribution），不在本 Advisor 做脆弱文本后处理。
 *
 * <p><b>v2.24 修正（簇⑤ B2，S3 护栏可观测）</b>：替换事件接
 * {@code rag.guardrail.output.replaced} 计数（同步 after() 与流式聚合后验两路径）。
 * 替换属非拒绝型干预（不抛异常），审计行仍落 SUCCESS + 安全话术 final_answer
 * （不加 error_code 标记，维持「SUCCESS→null」不变量）——观测走指标 + 话术取证。
 *
 * <p><b>v2.40 修正（安全簇① T2，词表结构化）</b>：输出黑名单由单行 CSV 升级为结构化
 * 词表——经 {@link GuardrailRulesLoader#loadOutputRules} 双源合并装载
 * {@link GuardrailRule}（结构化文件 ∪ {@code rag.guardrail.output.blacklist} 兼容并入）。
 * 命中按 {@code action} 分流：BLOCK 整段替换（语义不变）、FLAG 观察档放行只计数
 * （{@code rag.guardrail.flagged} T7 接入）。词项 value 编码态存储、加载层解码
 * （第七节敏感词交付纪律条 2）；KEYWORD 匹配为大小写不敏感子串（较旧版大小写敏感
 * contains 收紧拦截面，安全方向）。
 *
 * <p><b>v2.42 修正（安全簇① T5，输出面分类化 + 系统提示金丝雀）</b>：
 * <ul>
 *   <li><b>分类化替换</b>：BLOCK 命中按词项 {@code family}（{@link OutputFamily}
 *       三分类）选取对应安全话术，替换计数按分类落子项指标
 *       （{@code rag.guardrail.output.replaced.{分类}}，未知族系只计总项）；</li>
 *   <li><b>系统提示金丝雀</b>（OWASP LLM01 / Rebuff 同款）：校验先于词表判定——
 *       输出回显 {@link PromptCanary} 运行时随机 token 即确证提示泄露，整段替换
 *       + 独立指标 {@code rag.guardrail.output.canary}；</li>
 *   <li><b>PII 回显探测</b>（簇③ C2 接入闭环）：{@link #piiEchoHit} 经
 *       {@link PiiRecognizerRegistry} 检测视图探测回答中未掩码强形态 PII——
 *       FLAG 观察起步（计数 {@code rag.guardrail.output.pii.echo} + warn 类型事实，
 *       不替换不阻断），验证误报后再定动作（专项方案 §4.1 A3）。</li>
 * </ul>
 *
 * <p><b>v2.43 修正（安全簇① T7，FLAG 观察语义）</b>：FLAG 档命中（无 BLOCK 命中时）
 * 放行 + 计数 {@code rag.guardrail.flagged}（side=output + family 低基数标签）
 * + 写 {@link RetrievalContext.FlagMark} 审计标记。ctx 取用路径实证：同步 after()
 * 经 {@code response.context()}——终端 ChatModelCallAdvisor 以
 * {@code Map.copyOf(request.context())} 将 advisor 参数（含本实例）写入响应 context；
 * 流式 adviseStream 直接持有 request。BLOCK 替换路径不计 FLAG（内容未放行）。
 *
 * <p><b>v2.45 修正（安全簇③ C1/C2，PII 识别器注册表）</b>：簇① T5 预留的
 * {@link #piiEchoHit} 钩子接入 {@link PiiRecognizerRegistry} 检测视图——金丝雀校验
 * 之后、词表判定之前观察（金丝雀替换后的安全话术无观察价值）；观察语义只计数
 * 不替换，与 BLOCK 替换控制流正交。
 */
@Slf4j
@Component
public class OutputGuardrailAdvisor implements BaseAdvisor, GuardrailRulesListener {

    /** 默认/合规敏感分类安全话术 */
    private static final String SAFE_RESPONSE_COMPLIANCE = "抱歉，由于合规要求，无法提供该信息。";
    private static final String SAFE_RESPONSE_BUSINESS_CONFIDENTIAL =
        "抱歉，该内容涉及企业内部保密信息，无法提供。";
    private static final String SAFE_RESPONSE_COMPETITOR_COMPARISON =
        "抱歉，我们无法提供竞品对比相关的倾向性信息。";

    /** 生效结构化词表：注册表快照（安全簇⑥ F1 起 volatile 承接热重载推送），action 分流 */
    private volatile List<GuardrailRule> outputRules;

    /**
     * 流式形态快照（词表热重载时原子重算）：尾部保留窗宽度。仅 KEYWORD 词项与
     * 金丝雀参与窗口（有确定长度上界）；REGEX 轨 {@code find()} 可匹配任意长度、
     * 无窗口语义——不触发聚合回落，改走<b>流末检出 + REPLACE 追回</b>（分层检出：
     * KEYWORD 命中点亚秒截断，REGEX 流末判定追回，泄露窗口 = 答案播放时长，
     * 用户拍板接受追回语义）。
     */
    private volatile StreamingGuard streamingGuard;

    /** 流式形态快照：window = 最长拦截模式长度 - 1（经典流式子串匹配保留窗，下界 1） */
    private record StreamingGuard(int window) {}

    /** 护栏命中计数（簇⑤ B2 S3）——替换/金丝雀事件入 Prometheus */
    private final AiBusinessMetrics metrics;

    /** 系统提示金丝雀（T5）：回显校验先于词表判定 */
    private final PromptCanary canary;

    /** PII 识别器注册表（安全簇③ C2）：回显探测消费检测视图（只识别不掩码） */
    private final PiiRecognizerRegistry piiRegistry;

    /**
     * 装配构造器——双构造器形态必须显式钉 {@link Autowired}（Spring 6 多构造器
     * 无注解即回落无参构造器致启动失败）。词表经 {@link GuardrailRulesRegistry}
     * 取初始快照并订阅热重载推送（安全簇⑥ F1，免重启词表运营）。
     */
    @Autowired
    public OutputGuardrailAdvisor(
            GuardrailRulesRegistry rulesRegistry,
            AiBusinessMetrics metrics,
            PromptCanary canary,
            PiiRecognizerRegistry piiRegistry) {
        this.outputRules = rulesRegistry.currentOutputRules();
        this.metrics = metrics;
        this.canary = canary;
        this.piiRegistry = piiRegistry;
        this.streamingGuard = computeGuard(outputRules);
        rulesRegistry.subscribe(this);
        logRulesLoaded();
    }

    /** 测试装配版：词表源直装（不经注册表，永不热重载——单测确定性） */
    public OutputGuardrailAdvisor(
            @Value("${rag.guardrail.rules.output-location:}") String rulesLocation,
            @Value("${rag.guardrail.output.blacklist:}") String blacklistCsv,
            AiBusinessMetrics metrics,
            PromptCanary canary,
            PiiRecognizerRegistry piiRegistry) {
        this.outputRules = GuardrailRulesLoader.loadOutputRules(rulesLocation, blacklistCsv);
        this.metrics = metrics;
        this.canary = canary;
        this.piiRegistry = piiRegistry;
        this.streamingGuard = computeGuard(outputRules);
        logRulesLoaded();
    }

    /** 热重载推送承接（安全簇⑥ F1）：volatile 引用替换，in-flight 判定持旧快照不受影响 */
    @Override
    public void onOutputRulesUpdated(List<GuardrailRule> rules) {
        this.outputRules = rules;
        this.streamingGuard = computeGuard(rules);
    }

    /**
     * 流式形态快照计算：窗口 = max(启用 BLOCK KEYWORD 词长, 金丝雀 token 长) - 1，
     * 下界 1。金丝雀计入窗口（安全优先：金丝雀串永不出前端，代价 ≈40 字符的放行滞后）；
     * FLAG 词不拦截无窗口语义，不参与。
     */
    private StreamingGuard computeGuard(List<GuardrailRule> rules) {
        int maxLen = canary.enabled() ? canary.token().length() : 0;
        maxLen = Math.max(maxLen, rules.stream()
            .filter(r -> r.enabled() && r.action() == RuleAction.BLOCK
                && r.type() == RuleType.KEYWORD)
            .mapToInt(r -> r.value().length()).max().orElse(0));
        return new StreamingGuard(Math.max(maxLen - 1, 1));
    }

    private void logRulesLoaded() {
        long blocks = outputRules.stream().filter(r -> r.action() == RuleAction.BLOCK).count();
        if (outputRules.isEmpty()) {
            log.warn("输出护栏词表为空，无拦截词项（内容经 T4 带外通道注入后生效）");
        } else {
            log.info("输出检测词表加载: {} 条（BLOCK {} / FLAG {}）",
                outputRules.size(), blocks, outputRules.size() - blocks);
        }
        if (canary.enabled()) {
            log.info("系统提示金丝雀已启用（运行时随机 token，输出回显即拦截）");
        }
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    /** 同步路径拦截：金丝雀回显 → 分类词表命中整段替换（保留响应上下文供审计/溯源消费） */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String output = extractText(response);
        if (output == null) {
            return response;
        }
        // ctx 经响应 context 取（实证：终端 ChatModelCallAdvisor 以 Map.copyOf 写入请求 advisor 参数）
        RetrievalContext ctx = ctxOf(response.context());
        if (canary.leakedIn(output)) {
            metrics.recordOutputCanary();
            log.warn("系统提示金丝雀在输出中回显——确证提示泄露，整段替换");
            if (ctx != null) {
                ctx.markOutputReplaced(SAFE_RESPONSE_COMPLIANCE);
            }
            return replaceResponse(response, SAFE_RESPONSE_COMPLIANCE);
        }
        // PII 回显观察（簇③ C2）：只计数不替换，与词表判定控制流正交
        piiEchoHit(output);
        Optional<GuardrailRule> hit = blockHit(output, ctx);
        if (hit.isEmpty()) {
            return response;
        }
        metrics.recordOutputReplaced(hit.get().family());
        log.warn("输出命中敏感词表（词项 {}，族系 {}），整段替换为分类安全话术",
            hit.get().id(), hit.get().family());
        if (ctx != null) {
            ctx.markOutputReplaced(safeTextFor(hit.get().family()));
        }
        return replaceResponse(response, safeTextFor(hit.get().family()));
    }

    /**
     * 流式路径拦截（v2.109 双形态）：整流缓冲曾让三链路「生成完毕后瞬间出全部结果」
     * ——流式体验归零（GLM 思考 5-8s + 生成 10-30s 后一次性倾泻）。
     *
     * <p><b>增量放行（缺省，ctx 在场）</b>：逐块到达即判定 KEYWORD/金丝雀（有长度
     * 上界），除尾部保留窗（最长拦截模式长 - 1）外实时放行——安全不变量：放行只发生
     * 在「pending 全量判定无命中」时且滞后窗保证任何 KEYWORD 命中在完成时必整体落在
     * pending 内被捕获，命中词及其后文本永不出流。命中即<b>吞块截断</b>（继续消费上游
     * 保证观察视图完整，不再放行）+ ctx 打标（{@link RetrievalContext#markOutputReplaced}）
     * ——SSE REPLACE 帧（Controller 流末）、归档 answer、审计 final_answer、语义缓存
     * 写入门槛四处消费点凭标记把已放行前缀整段追回替换为安全话术（泄露窗口 = 命中点前
     * 合规前缀的播放延迟，亚秒级，用户拍板接受）。<b>REGEX 轨无窗口上界，不参与流中
     * 判定</b>——流末对全文检出，命中同样打标追回（分层检出，泄露窗口 = 答案播放时长）。
     *
     * <p><b>聚合后验（保守回落）</b>：ctx 缺席（话术信号无参数链通道，测试/评估宿主）
     * 时保持原整流缓冲形态——违规以单个替换块下发，零泄露。
     *
     * <p>FLAG 观察 + PII 回显探测不拦截，两种形态均在流末对全文统一执行（观察对象
     * = 原文全文，语义一致）。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        RetrievalContext ctx = ctxOf(request.context());
        if (ctx == null) {
            return aggregateAndVerify(request, chain, ctx);
        }
        return incrementalStream(request, chain, ctx, streamingGuard.window());
    }

    /** 聚合后验（原 3.6 形态，ctx 缺席时保守回落）：整流缓冲，违规单块替换 */
    private Flux<ChatClientResponse> aggregateAndVerify(ChatClientRequest request,
                                                        StreamAdvisorChain chain,
                                                        RetrievalContext ctx) {
        return chain.nextStream(request)
            .collectList()
            .flatMapMany(responses -> {
                String fullText = responses.stream()
                    .map(OutputGuardrailAdvisor::extractText)
                    .filter(text -> text != null)
                    .collect(Collectors.joining());
                if (canary.leakedIn(fullText)) {
                    metrics.recordOutputCanary();
                    log.warn("流式输出回显系统提示金丝雀——确证提示泄露，整段替换");
                    ChatClientResponse last = responses.isEmpty() ? null : responses.get(responses.size() - 1);
                    if (ctx != null) {
                        ctx.markOutputReplaced(SAFE_RESPONSE_COMPLIANCE);
                    }
                    return Flux.just(replaceResponse(last, SAFE_RESPONSE_COMPLIANCE));
                }
                // PII 回显观察（簇③ C2）：聚合后验只计数不替换
                piiEchoHit(fullText);
                Optional<GuardrailRule> hit = blockHit(fullText, ctx);
                if (hit.isPresent()) {
                    metrics.recordOutputReplaced(hit.get().family());
                    log.warn("流式输出命中敏感词表（词项 {}，族系 {}），整段替换",
                        hit.get().id(), hit.get().family());
                    ChatClientResponse last = responses.isEmpty() ? null : responses.get(responses.size() - 1);
                    if (ctx != null) {
                        ctx.markOutputReplaced(safeTextFor(hit.get().family()));
                    }
                    return Flux.just(replaceResponse(last, safeTextFor(hit.get().family())));
                }
                return Flux.fromIterable(responses);
            });
    }

    /**
     * 增量放行：逐块判定 + 尾部保留窗 + 命中吞块截断。
     *
     * <p>吞块式截断（非 complete()）：命中后继续消费上游（fullText 观察视图完整、
     * 无 SynchronousSink 终止后 next 的竞态），但不再放行任何内容；流自然完成时
     * concatWith 段凭截断标记跳过尾部窗 flush。
     */
    private Flux<ChatClientResponse> incrementalStream(ChatClientRequest request,
                                                       StreamAdvisorChain chain,
                                                       RetrievalContext ctx,
                                                       int window) {
        StringBuilder pending = new StringBuilder();
        StringBuilder fullText = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();
        return chain.nextStream(request)
            .<ChatClientResponse>handle((response, sink) -> {
                String text = extractText(response);
                if (text != null && !text.isEmpty()) {
                    pending.append(text);
                    fullText.append(text);
                }
                if (truncated.get()) {
                    return;   // 截断后迟到块：只累积观察视图，不再放行
                }
                if (text == null || text.isEmpty()) {
                    sink.next(response);   // 空文本块（usage/finishReason 元数据帧）无泄露面，原样透传
                    return;
                }
                String view = TextSanitizer.normalize(pending.toString());
                if (canary.leakedIn(view)) {
                    metrics.recordOutputCanary();
                    log.warn("流式输出回显系统提示金丝雀——确证提示泄露，截断并追回（REPLACE 帧）");
                    ctx.markOutputReplaced(SAFE_RESPONSE_COMPLIANCE);
                    truncated.set(true);
                    return;
                }
                Optional<GuardrailRule> hit = blockOnly(view);
                if (hit.isPresent()) {
                    metrics.recordOutputReplaced(hit.get().family());
                    log.warn("流式输出命中敏感词表（词项 {}，族系 {}），截断并追回（REPLACE 帧）",
                        hit.get().id(), hit.get().family());
                    ctx.markOutputReplaced(safeTextFor(hit.get().family()));
                    truncated.set(true);
                    return;
                }
                int safe = pending.length() - window;
                if (safe > 0) {
                    sink.next(recombined(response, pending.substring(0, safe)));
                    pending.delete(0, safe);
                }
            })
            .concatWith(Mono.defer(() -> {
                // 流末统一判定：REGEX 轨完整形态检出（流中仅前缀可匹配的早截断）+
                // FLAG 观察 + PII 回显（观察对象 = 原文全文，与聚合形态语义一致）
                String full = fullText.toString();
                String view = TextSanitizer.normalize(full);
                if (!truncated.get()) {
                    Optional<GuardrailRule> lateHit = blockOnly(view);
                    if (lateHit.isPresent()) {
                        metrics.recordOutputReplaced(lateHit.get().family());
                        log.warn("流末判定命中敏感词表（词项 {}，族系 {}——REGEX 轨完整形态），追回（REPLACE 帧）",
                            lateHit.get().id(), lateHit.get().family());
                        ctx.markOutputReplaced(safeTextFor(lateHit.get().family()));
                        truncated.set(true);
                    }
                }
                piiEchoHit(full);
                blockHit(view, ctx);   // FLAG 分支（BLOCK 在场时自动跳过——「BLOCK 不计 FLAG」语义保持）
                if (truncated.get() || pending.isEmpty()) {
                    return Mono.<ChatClientResponse>empty();
                }
                // 放行尾部保留窗（流完成即无后续，窗口使命结束）；usage 等元数据
                // 已随空文本帧透传或流中重组块携带，此块无需 metadata
                return Mono.just(recombined(null, pending.toString()));
            }));
    }

    /** 纯判定无副作用（增量形态流中高频调用，FLAG 观察留流末统一执行） */
    private Optional<GuardrailRule> blockOnly(String normalizedView) {
        return TextSanitizer.matchRules(normalizedView, outputRules).stream()
            .filter(r -> r.action() == RuleAction.BLOCK)
            .findFirst();
    }

    /** 重组放行块：文本切片 + 保留原响应 metadata（流中 usage 计量传播链不断） */
    private static ChatClientResponse recombined(ChatClientResponse prototype, String text) {
        ChatResponse original = prototype != null ? prototype.chatResponse() : null;
        ChatResponse rebuilt = original != null && original.getMetadata() != null
            ? new ChatResponse(List.of(new Generation(new AssistantMessage(text))), original.getMetadata())
            : new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return ChatClientResponse.builder()
            .chatResponse(rebuilt)
            .context(prototype != null ? prototype.context() : Map.of())
            .build();
    }

    @Override
    public int getOrder() {
        return 110;
    }

    /**
     * 结构化词表命中判定（action 分流）：返回首个 BLOCK 命中词项；
     * 仅 FLAG 命中时记录观察（T7：计数 rag.guardrail.flagged + 审计标记，放行不替换）。
     * BLOCK 替换路径不计 FLAG——被替换内容未放行，其 FLAG 族系无观察价值。
     */
    private Optional<GuardrailRule> blockHit(String text, RetrievalContext ctx) {
        List<GuardrailRule> matched = TextSanitizer.matchRules(text, outputRules);
        Optional<GuardrailRule> block = matched.stream()
            .filter(r -> r.action() == RuleAction.BLOCK)
            .findFirst();
        if (block.isEmpty() && !matched.isEmpty()) {
            List<String> families = matched.stream()
                .map(r -> new RetrievalContext.FlagMark(AiBusinessMetrics.SIDE_OUTPUT, r.family()).family())
                .distinct()
                .toList();
            for (String family : families) {
                metrics.recordFlagged(AiBusinessMetrics.SIDE_OUTPUT, family);
                if (ctx != null) {
                    ctx.addGuardrailFlag(new RetrievalContext.FlagMark(AiBusinessMetrics.SIDE_OUTPUT, family));
                }
            }
            log.info("输出词表 FLAG 观察档命中 {} 条，放行（族系 {}）", matched.size(), families);
        }
        return block;
    }

    /** 从 advisor 参数 context 提取检索上下文（无则 null——非 Web 入口只计数不写审计标记） */
    private static RetrievalContext ctxOf(Map<String, Object> context) {
        return context != null && context.get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext rc
            ? rc : null;
    }

    /** PII 回显探测（簇① T5 钩子，安全簇③ C2 接入）：识别器注册表检测视图探测
     * 回答中未掩码强形态 PII → FLAG 观察起步——计数 + warn 类型事实，不替换不阻断 */
    private boolean piiEchoHit(String text) {
        List<PiiHit> hits = piiRegistry.detect(text);
        if (hits.isEmpty()) {
            return false;
        }
        List<PiiType> types = hits.stream().map(PiiHit::type).distinct().toList();
        metrics.recordOutputPiiEcho();
        log.warn("输出检出未掩码 PII 回显（类型 {}），FLAG 观察档放行不替换", types);
        return true;
    }

    /** 分类安全话术：未知族系/UNCLASSIFIED 落默认合规话术 */
    private static String safeTextFor(String family) {
        if (family == null) {
            return SAFE_RESPONSE_COMPLIANCE;
        }
        try {
            return switch (OutputFamily.valueOf(family.trim().toUpperCase())) {
                case BUSINESS_CONFIDENTIAL -> SAFE_RESPONSE_BUSINESS_CONFIDENTIAL;
                case COMPLIANCE_SENSITIVE -> SAFE_RESPONSE_COMPLIANCE;
                case COMPETITOR_COMPARISON -> SAFE_RESPONSE_COMPETITOR_COMPARISON;
            };
        } catch (IllegalArgumentException e) {
            return SAFE_RESPONSE_COMPLIANCE;
        }
    }

    /** 空安全文本提取（响应/结果/输出任一环节为空均返回 null） */
    private static String extractText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    /** 替换响应：给定安全话术 + 保留原响应上下文（无原响应时以空上下文兜底） */
    private static ChatClientResponse replaceResponse(ChatClientResponse original, String safeText) {
        ChatClientResponse.Builder builder = ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(safeText)))));
        if (original != null && original.context() != null) {
            builder.context(original.context());
        }
        return builder.build();
    }
}
