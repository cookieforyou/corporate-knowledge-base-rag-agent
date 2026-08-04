package com.enterprise.kb.ai.service;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * RAG 对话服务 — 封装 Agent ChatClient 调用
 *
 * <p>3.1 起走 {@code agentChatClient}（记忆 + 检索链路）；kb-eval 仍直注
 * {@code chatClient} Bean（纯 RAG，评估基线不受影响）。
 *
 * <p><b>会话 ID 参数链</b>：sessionId 由 Controller 经 advisor 参数
 * （{@link ChatMemory#CONVERSATION_ID}）传入记忆 Advisor——与 RetrievalContext
 * 同款参数化机制（不用 @RequestScope/ThreadLocal；MVC 异步请求完结后作用域
 * 代理不可解析，流式生命周期内必失效）。缺失会话 ID 时
 * BaseChatMemoryAdvisor 为硬断言失败，故 Controller 必须保证非空
 * （请求未携带时由 Controller 生成）。
 *
 * <p>调用方（kb-api Controller）在请求线程创建并填充 {@link RetrievalContext}
 * （租户/用户身份），同样经 advisor 参数传入检索组件——同步与流式一致。
 */
@Service
public class ChatService {

    private final ChatClient chatClient;

    /** 显式构造器注入：Lombok 不复制字段 @Qualifier 至构造参数，双 ChatClient Bean 下须显式限定 */
    public ChatService(@Qualifier("agentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 同步多轮 RAG 问答 */
    public String chat(String query, String sessionId, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .call()
            .content();
    }

    /** 流式多轮 RAG 问答 */
    public Flux<String> chatStream(String query, String sessionId, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .stream()
            .content();
    }
}
