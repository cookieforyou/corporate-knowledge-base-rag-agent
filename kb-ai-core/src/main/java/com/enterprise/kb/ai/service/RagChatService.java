package com.enterprise.kb.ai.service;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * RAG 对话服务（设计文档 11.5，任务 3.19 双链路拆分自 ChatService）——
 * 封装 ragAgentChatClient 调用，**纯检索问答，无工具语义**。
 *
 * <p>签名上物理消除 approvedToolCallId / toolContext（rag 链无工具消费方）——
 * HITL 凭证与工具上下文通道见 kb-ai-agent 的 ToolChatService。
 *
 * <p><b>会话 ID 参数链</b>：sessionId 由 Controller 经 advisor 参数
 * （{@link ChatMemory#CONVERSATION_ID}）传入记忆 Advisor——与 RetrievalContext
 * 同款参数化机制（不用 @RequestScope/ThreadLocal；MVC 异步请求完结后作用域
 * 代理不可解析，流式生命周期内必失效）。缺失会话 ID 时
 * BaseChatMemoryAdvisor 为硬断言失败，故 Controller 必须保证非空
 * （请求未携带时由 Controller 生成）。
 *
 * <p>kb-eval 仍直注 {@code chatClient} Bean（纯 RAG 评估链），评估基线不受
 * 生产链演进影响。
 */
@Service
public class RagChatService {

    private final ChatClient chatClient;

    /** 显式构造器注入：多 ChatClient Bean 必须 @Qualifier 限定（3.2 @Primary 歧义教训） */
    public RagChatService(@Qualifier("ragAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /** 同步多轮 RAG 问答 */
    public String chatRag(String query, String sessionId, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .call()
            .content();
    }

    /** 流式多轮 RAG 问答 */
    public Flux<String> chatStreamRag(String query, String sessionId, RetrievalContext retrievalContext) {
        return chatClient.prompt()
            .user(query)
            .advisors(spec -> spec
                .param(ChatMemory.CONVERSATION_ID, sessionId)
                .param(RetrievalContext.CONTEXT_KEY, retrievalContext))
            .stream()
            .content();
    }
}
