package com.enterprise.kb.ai.service;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * RAG 对话服务 — 封装 ChatClient 调用
 *
 * <p>调用方（kb-api Controller）在请求线程创建并填充 {@link RetrievalContext}
 * （租户/用户身份），经 advisor 参数随请求链路传入检索组件——同步与流式一致，
 * 不依赖请求作用域与线程模型（2026-08-02 重构）。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    /** 同步 RAG 问答 */
    public String chat(String query, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec.param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .call()
            .content();
    }

    /** 流式 RAG 问答 */
    public Flux<String> chatStream(String query, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec.param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .stream()
            .content();
    }
}
