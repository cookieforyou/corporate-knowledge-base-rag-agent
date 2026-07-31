package com.enterprise.kb.ai.config;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.constant.Constants;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 检索组件装配（设计文档 10.6 的检索部分；Advisor 组装在 2.10 补入）
 */
@Configuration
public class RetrievalConfig {

    /**
     * 向量路检索器 —— topK 固化为 recallSize（VectorStoreDocumentRetriever 无实例级
     * withTopK，构建期定型）；安全过滤表达式按请求从 RetrievalContext 动态读取
     */
    @Bean
    public VectorStoreDocumentRetriever vectorStoreDocumentRetriever(
            VectorStore vectorStore,
            ObjectProvider<RetrievalContext> retrievalContextProvider) {
        return VectorStoreDocumentRetriever.builder()
            .vectorStore(vectorStore)
            .similarityThreshold(0.5)
            .topK(Constants.DEFAULT_TOP_K * 2)
            .filterExpression(() -> {
                // 非 Web 上下文（kb-eval 等）无请求作用域，降级为不过滤
                if (RequestContextHolder.getRequestAttributes() == null) {
                    return null;
                }
                try {
                    RetrievalContext ctx = retrievalContextProvider.getObject();
                    return ctx != null ? ctx.getSecurityFilter() : null;
                } catch (Exception e) {
                    return null;
                }
            })
            .build();
    }
}
