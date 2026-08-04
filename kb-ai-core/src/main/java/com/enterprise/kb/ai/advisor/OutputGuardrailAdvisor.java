package com.enterprise.kb.ai.advisor;

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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>L1 形态（12.2.1）：黑名单规则链，词表经 {@code rag.guardrail.output.blacklist}
 * 配置（生产接配置中心动态加载为升级项）。幻觉拦截（引用忠实性）归评估体系
 * （16.2 Citation Attribution），不在本 Advisor 做脆弱文本后处理。
 */
@Slf4j
@Component
public class OutputGuardrailAdvisor implements BaseAdvisor {

    private static final String SAFE_RESPONSE = "抱歉，由于合规要求，无法提供该信息。";

    private final Set<String> blacklist;

    public OutputGuardrailAdvisor(
            @Value("${rag.guardrail.output.blacklist:competitor_x,competitor_y}") String blacklistCsv) {
        this.blacklist = Stream.of(blacklistCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toSet());
        if (blacklist.isEmpty()) {
            log.warn("rag.guardrail.output.blacklist 为空，输出护栏无拦截词表");
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
        if (output == null || !containsBlacklisted(output)) {
            return response;
        }
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
                if (containsBlacklisted(fullText)) {
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

    private boolean containsBlacklisted(String text) {
        return blacklist.stream().anyMatch(text::contains);
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
