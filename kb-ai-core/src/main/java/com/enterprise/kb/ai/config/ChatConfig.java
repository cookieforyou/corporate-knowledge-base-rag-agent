package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.advisor.InputSanitizeAdvisor;
import com.enterprise.kb.ai.advisor.RetrievalTraceAdvisor;
import com.enterprise.kb.ai.advisor.SemanticInjectionAdvisor;
import com.enterprise.kb.ai.prompt.PromptTemplates;
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
    public ChatClient chatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                 RetrievalTraceAdvisor retrievalTraceAdvisor,
                                 RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultSystem(PromptTemplates.EVAL_SYSTEM_PROMPT)
            .defaultAdvisors(retrievalTraceAdvisor, retrievalAugmentationAdvisor)
            .build();
    }

    /**
     * INJECTION 用例专属护栏链（簇⑤ B2 S6）：仅挂 InputSanitizeAdvisor——
     * 注入拦截率度量只测 L1 输入护栏本身，不挂配额/限流/审计/输出护栏
     * （免 429 污染判定、免审计表注入样本噪声、免输出替换干扰）。
     * 被测模型复用 smartRoutingChatModel（与被测 chatClient 同源）。
     */
    @Bean
    public ChatClient evalGuardrailChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                              InputSanitizeAdvisor inputSanitizeAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultSystem(PromptTemplates.EVAL_SYSTEM_PROMPT)
            .defaultAdvisors(inputSanitizeAdvisor)
            .build();
    }

    /**
     * INJECTION 用例 L1+L2 联合护栏链（安全簇⑤ E2）：InputSanitize(300) +
     * SemanticInjection(320) 双 advisor——与 evalGuardrailChatClient（L1 单独读数）
     * 对偶，产出「L1+L2 联合」读数（门禁治 L2 判别力，用户定案 2026-08-18）。
     * EvalRunner 调用点经 {@code .param(SemanticInjectionAdvisor.FORCE_JUDGE_KEY, true)}
     * 力判直通：L1 未拦样本逐条进 L2 判定——力判键只存在于 eval 链 context，
     * 生产链与 chain-probe 干净集不携带不受污染。
     */
    @Bean
    public ChatClient evalGuardrailL2ChatClient(@Qualifier("smartRoutingChatModel") ChatModel chatModel,
                                                InputSanitizeAdvisor inputSanitizeAdvisor,
                                                SemanticInjectionAdvisor semanticInjectionAdvisor) {
        return ChatClient.builder(chatModel)
            .defaultSystem(PromptTemplates.EVAL_SYSTEM_PROMPT)
            .defaultAdvisors(inputSanitizeAdvisor, semanticInjectionAdvisor)
            .build();
    }
}
