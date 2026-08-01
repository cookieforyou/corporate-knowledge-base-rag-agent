package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.HashMap;
import java.util.Map;

/**
 * 检索溯源 Advisor（设计文档 11.1.1，任务 2.11）—— Order 450
 *
 * <p>双职责：
 * <ul>
 *   <li>before：Advisor 链入口从当前请求身份（{@link RequestIdentityResolver}，
 *       kb-api 的 JWT 实现）填充请求级 {@link RetrievalContext} 的 tenantId/userId——
 *       供 ES BM25 路 term 过滤（2.6）与向量路 filterExpression（2.7）消费；</li>
 *   <li>after：检索 trace（双路原始命中 + 重排后 final 序列，由各检索组件经
 *       RetrievalContext.addTraceEntry 记录）旁路写入响应上下文，供 Controller 层
 *       SSE TRACE 事件（2.12）与审计消费。</li>
 * </ul>
 *
 * <p>流式兼容：Reactor 链路跨线程后响应上下文难以取回，故溯源主通道是请求作用域
 * RetrievalContext 旁路（11.3 注）——Controller 在请求线程捕获实例，流末读取。
 * 非 Web 上下文（kb-eval）无请求作用域，全程降级跳过（检索不做租户过滤、不记 trace）。
 *
 * <p>v2 API 形态（源码核验）：ChatClientRequest/Response 是 record，位于
 * org.springframework.ai.chat.client 包（非 advisor.api）；经 record 构造器重建。
 */
@Component
public class RetrievalTraceAdvisor implements BaseAdvisor {

    private final ObjectProvider<RetrievalContext> retrievalContextProvider;
    private final ObjectProvider<RequestIdentityResolver> identityResolverProvider;

    public RetrievalTraceAdvisor(ObjectProvider<RetrievalContext> retrievalContextProvider,
                                 ObjectProvider<RequestIdentityResolver> identityResolverProvider) {
        this.retrievalContextProvider = retrievalContextProvider;
        this.identityResolverProvider = identityResolverProvider;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        RetrievalContext ctx = requestContext();
        if (ctx == null) {
            return request;   // 非 Web 上下文（kb-eval）：纯透传，不重建请求
        }
        RequestIdentityResolver identity = identityResolver();
        if (identity != null) {
            ctx.setTenantId(identity.getTenantId());
            ctx.setUserId(identity.getUserId());
        }
        // 透传检索起始时刻（调试 API 10.7 / 时延观测用）
        Map<String, Object> context = new HashMap<>(request.context());
        context.put("trace_start_ms", System.currentTimeMillis());
        return new ChatClientRequest(request.prompt(), context);
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        RetrievalContext ctx = requestContext();
        if (ctx == null) {
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

    /** 请求上下文安全访问：非 Web 上下文返回 null（评估/异步场景降级） */
    private RetrievalContext requestContext() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return null;
        }
        try {
            return retrievalContextProvider.getObject();
        } catch (Exception e) {
            return null;
        }
    }

    private RequestIdentityResolver identityResolver() {
        try {
            return identityResolverProvider.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }
}
