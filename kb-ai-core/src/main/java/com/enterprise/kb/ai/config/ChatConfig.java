package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置 — 注入 QuestionAnswerAdvisor 实现基础 RAG
 */
@Configuration
public class ChatConfig {

    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel, VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
            .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                    .topK(5)
                    .similarityThreshold(0.5)
                    .build())
                .build())
            .build();
    }
}
