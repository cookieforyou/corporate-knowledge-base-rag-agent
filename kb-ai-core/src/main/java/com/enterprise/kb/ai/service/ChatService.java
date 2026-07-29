package com.enterprise.kb.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 基础 RAG 对话服务 — 封装 ChatClient 调用
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    /**
     * 同步 RAG 问答
     */
    public String chat(String query) {
        return chatClient.prompt()
            .user(query)
            .call()
            .content();
    }

    /**
     * 流式 RAG 问答
     */
    public Flux<String> chatStream(String query) {
        return chatClient.prompt()
            .user(query)
            .stream()
            .content();
    }
}
