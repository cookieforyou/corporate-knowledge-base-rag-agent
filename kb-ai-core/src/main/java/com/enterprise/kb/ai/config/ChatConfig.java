package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.advisor.RetrievalTraceAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 装配 —— 被测主链路（设计文档 11.2）
 *
 * <p>2.10 起以模块化 RAG（RetrievalAugmentationAdvisor）替代 Phase 1 的
 * QuestionAnswerAdvisor：查询改写 + 双路混合检索 + RRF 融合 + 精排 + Grounding 注入。
 * Bean 名保持 {@code chatClient}：kb-eval 经 @Qualifier 注入本 Bean，被测链路
 * 切换对评估器零感知（16.5 承诺）。
 *
 * <p>Advisor 链序（11.2）：RetrievalTraceAdvisor(450，2.11 加入) →
 * RetrievalAugmentationAdvisor(500)。
 */
@Configuration
public class ChatConfig {

    @Bean
    public ChatClient chatClient(@Qualifier("deepSeekChatModel") ChatModel chatModel,
                                 RetrievalTraceAdvisor retrievalTraceAdvisor,
                                 RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(retrievalTraceAdvisor, retrievalAugmentationAdvisor)
            .build();
    }
}
