package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.guardrail.GuardrailRule;
import com.enterprise.kb.commons.guardrail.GuardrailRulesLoader;
import com.enterprise.kb.commons.guardrail.RuleAction;
import com.enterprise.kb.commons.security.TextSanitizer;
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
import java.util.stream.Collectors;

/**
 * 输出安全护栏（设计文档 12.2，任务 3.6）—— 敏感词/竞品黑名单拦截替换
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
 */
@Slf4j
@Component
public class OutputGuardrailAdvisor implements BaseAdvisor {

    private static final String SAFE_RESPONSE = "抱歉，由于合规要求，无法提供该信息。";

    /** 生效结构化词表：双源合并（结构化文件 ∪ CSV 兼容），action 分流 */
    private final List<GuardrailRule> outputRules;

    /** 护栏命中计数（簇⑤ B2 S3）——黑名单替换事件入 Prometheus */
    private final AiBusinessMetrics metrics;

    public OutputGuardrailAdvisor(
            @Value("${rag.guardrail.rules.output-location:}") String rulesLocation,
            @Value("${rag.guardrail.output.blacklist:}") String blacklistCsv,
            AiBusinessMetrics metrics) {
        this.outputRules = GuardrailRulesLoader.loadOutputRules(rulesLocation, blacklistCsv);
        this.metrics = metrics;
        long blocks = outputRules.stream().filter(r -> r.action() == RuleAction.BLOCK).count();
        if (outputRules.isEmpty()) {
            log.warn("输出护栏词表为空，无拦截词项");
        } else {
            log.info("输出检测词表加载: {} 条（BLOCK {} / FLAG {}）",
                outputRules.size(), blocks, outputRules.size() - blocks);
        }
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    /** 同步路径拦截：命中黑名单整段替换（保留响应上下文供审计/溯源消费） */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String output = extractText(response);
        if (output == null || !blocked(output)) {
            return response;
        }
        metrics.recordOutputReplaced();
        log.warn("输出命中敏感词黑名单，已替换为安全话术");
        return replaceResponse(response);
    }

    /**
     * 流式路径拦截：聚合后验。缓冲全部块后统一判定——违规以单个替换块下发
     * （前端按 token 追加协议收到安全话术）；合规则原样顺序放行所有块，
     * onFinishReason 等元数据完整保留。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
            .collectList()
            .flatMapMany(responses -> {
                String fullText = responses.stream()
                    .map(OutputGuardrailAdvisor::extractText)
                    .filter(text -> text != null)
                    .collect(Collectors.joining());
                if (blocked(fullText)) {
                    metrics.recordOutputReplaced();
                    log.warn("流式输出命中敏感词黑名单，整段替换为安全话术");
                    ChatClientResponse last = responses.isEmpty() ? null : responses.get(responses.size() - 1);
                    return Flux.just(replaceResponse(last));
                }
                return Flux.fromIterable(responses);
            });
    }

    @Override
    public int getOrder() {
        return 110;
    }

    /** 结构化词表命中判定（action 分流）：BLOCK 命中即替换；FLAG 观察档放行只计数（T7 接指标） */
    private boolean blocked(String text) {
        List<GuardrailRule> matched = TextSanitizer.matchRules(text, outputRules);
        if (matched.isEmpty()) {
            return false;
        }
        boolean block = matched.stream().anyMatch(r -> r.action() == RuleAction.BLOCK);
        if (!block) {
            log.info("输出词表 FLAG 观察档命中 {} 条，放行", matched.size());
        }
        return block;
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

    /** 替换响应：安全话术 + 保留原响应上下文（无原响应时以空上下文兜底） */
    private static ChatClientResponse replaceResponse(ChatClientResponse original) {
        ChatClientResponse.Builder builder = ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(SAFE_RESPONSE)))));
        if (original != null && original.context() != null) {
            builder.context(original.context());
        }
        return builder.build();
    }
}
