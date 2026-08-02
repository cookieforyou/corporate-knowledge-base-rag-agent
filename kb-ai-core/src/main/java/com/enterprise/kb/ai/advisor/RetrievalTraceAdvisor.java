package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索溯源 Advisor（设计文档 11.1.1，任务 2.11）—— Order 450
 *
 * <p>2026-08-02 重构后的瘦形态：请求级 {@link RetrievalContext} 由 Controller 创建、
 * 在请求线程填充身份（JwtUtils），经 advisor 参数（{@link RetrievalContext#CONTEXT_KEY}）
 * 随 Query.context 流入检索组件——本 Advisor 不再承担身份填充（原设计稿在 kb-ai-core
 * 引用 kb-api 的 JwtUtils 亦违反模块依赖方向，随重构一并消除）。
 *
 * <p>现存职责：
 * <ul>
 *   <li>before：请求上下文打检索起始时刻戳（调试 API 10.7 / 时延观测）；</li>
 *   <li>after：若响应上下文携带 RetrievalContext（advisor 参数随链路透传），
 *       将 trace 快照写入响应上下文，供同步链路的调试 API（2.14）消费。
 *       流式链路的 TRACE 走 Controller 直读同一实例的旁路（11.3），不经此处。</li>
 * </ul>
 *
 * <p>全程只操作请求/响应上下文的 Map，不解引用任何作用域代理——线程模型无关。
 */
@Component
public class RetrievalTraceAdvisor implements BaseAdvisor {

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        Map<String, Object> context = new HashMap<>(request.context());
        context.put("trace_start_ms", System.currentTimeMillis());
        return new ChatClientRequest(request.prompt(), context);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!(response.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx)) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.put("rag_trace", ctx.getTraceSummary());
        context.put("retrieval_count", ctx.getTraceSummary().size());
        context.put("top_fusion_score", ctx.getTopFusionScore());
        return ChatClientResponse.builder()
            .chatResponse(response.chatResponse())
            .context(context)
            .build();
    }

    /** 11.2 链序表：检索溯源先于 RetrievalAugmentationAdvisor(500) */
    @Override
    public int getOrder() {
        return 450;
    }
}
