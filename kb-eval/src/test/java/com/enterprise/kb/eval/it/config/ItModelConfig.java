package com.enterprise.kb.eval.it.config;

import com.enterprise.kb.ai.routing.SmartRoutingChatModel;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import com.enterprise.kb.eval.it.stub.StubEmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * IT 模型桩装配（簇⑥ D3）——替代被排除的 {@code SmartRoutingConfig} 与让位的
 * embedding 自动装配：
 *
 * <ul>
 *   <li>{@code smartRoutingChatModel}（@Primary）：真实 {@link SmartRoutingChatModel}
 *       包装桩主模型（fallback=null 单模型形态）——流式 usage 透传（簇③ D1 计账）、
 *       链上所有 ChatClient（ragAgentChatClient / chatClient / evalGuardrailChatClient /
 *       QueryRoutingAdvisor 分类器经 ChatClient.Builder）全走同一桩</li>
 *   <li>{@code EmbeddingModel}：确定性 hashing trick 桩，VectorStoreConfig 按类型注入</li>
 * </ul>
 */
@Configuration
public class ItModelConfig {

    @Bean
    public StubChatModel stubChatModel() {
        return new StubChatModel();
    }

    @Bean
    @Primary
    public ChatModel smartRoutingChatModel(StubChatModel stubChatModel) {
        return new SmartRoutingChatModel(stubChatModel, null, 5, 30);
    }

    @Bean
    public EmbeddingModel stubEmbeddingModel() {
        return new StubEmbeddingModel();
    }
}
