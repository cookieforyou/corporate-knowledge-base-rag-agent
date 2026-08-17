package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.guardrail.PromptCanary;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.OutputFamily;
import com.enterprise.kb.commons.guardrail.RuleAction;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 输出安全护栏（设计文档 12.2，任务 3.6）—— 敏感词拦截替换 + 系统提示金丝雀
 *
 * <p>Order 110：after() 在内层 Advisor（记忆/检索）之后执行，审查最终输出。
 *
 * <p><b>流式语义修正（12 章草稿未覆盖）</b>：BaseAdvisor 默认 adviseStream 仅对
 * onFinishReason 末块执行 after()——违规 token 此前已逐个流出，无法追回。
 * 合规优先于 TTFT：本 Advisor 覆写 adviseStream 为<b>聚合后验</b>——缓冲完整
 * 回答，违规则整段替换为安全话术，合规则原样顺序放行全部块（内容不变，
 * 仅到达时刻后移）。同步路径（/chat）经默认 adviseCall + after() 全量拦截。
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
public class OutputGuardrailAdvisor implements BaseAdvisor {

    /** 默认/合规敏感分类安全话术 */
    private static final String SAFE_RESPONSE_COMPLIANCE = "抱歉，由于合规要求，无法提供该信息。";
    private static final String SAFE_RESPONSE_BUSINESS_CONFIDENTIAL =
        "抱歉，该内容涉及企业内部保密信息，无法提供。";
    private static final String SAFE_RESPONSE_COMPETITOR_COMPARISON =
        "抱歉，我们无法提供竞品对比相关的倾向性信息。";

    /** 生效结构化词表：双源合并（结构化文件 ∪ CSV 兼容），action 分流 */
    private final List<GuardrailRule> outputRules;

    /** 护栏命中计数（簇⑤ B2 S3）——替换/金丝雀事件入 Prometheus */
    private final AiBusinessMetrics metrics;

    /** 系统提示金丝雀（T5）：回显校验先于词表判定 */
    private final PromptCanary canary;

    /** PII 识别器注册表（安全簇③ C2）：回显探测消费检测视图（只识别不掩码） */
    private final PiiRecognizerRegistry piiRegistry;

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
        return replaceResponse(response, safeTextFor(hit.get().family()));
    }

    /**
     * 流式路径拦截：聚合后验。缓冲全部块后统一判定——违规以单个替换块下发
     * （前端按 token 追加协议收到安全话术）；合规则原样顺序放行所有块，
     * onFinishReason 等元数据完整保留。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        RetrievalContext ctx = ctxOf(request.context());
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
                    return Flux.just(replaceResponse(last, safeTextFor(hit.get().family())));
                }
                return Flux.fromIterable(responses);
            });
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
