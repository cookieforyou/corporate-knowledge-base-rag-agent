package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import reactor.core.publisher.Flux;

/**
 * 检索门控 Advisor（5.4 收窄版）—— Order 500，组合式包裹 {@link RetrievalAugmentationAdvisor}
 *
 * <p>读 {@link RetrievalContext#isSkipRetrieval()} 标记（QueryRoutingAdvisor 440 写入）：
 * <ul>
 *   <li>skip=true → 不调 delegate，{@code chain.nextCall/nextStream} 直接放行——
 *       改写/双路检索/RRF/重排/grounding 全套旁路，模型携记忆直答</li>
 *   <li>skip=false / 无上下文 → 委托 {@code delegate.adviseCall/adviseStream}（完整 RAG 管线）</li>
 * </ul>
 *
 * <p><b>形态修正（对设计稿 11.4「Advisor 自身读标记短路」的落地修正）</b>：
 * 源码核验 RetrievalAugmentationAdvisor 为 final class，不可 extends；其
 * BaseAdvisor before/after 钩子亦无法跳过自身处理——故以组合式门控实现同语义。
 * 本 Advisor 替代 RetrievalAugmentationAdvisor 挂入 rag 链（delegate 降为内部
 * 组件，仍是容器 Bean 供本门控注入）；直接实现 CallAdvisor + StreamAdvisor
 * 而非 BaseAdvisor——后者的默认 adviseStream 模板（before→nextStream→after）
 * 无法表达「跳过 delegate」语义。
 */
public class RetrievalGateAdvisor implements CallAdvisor, StreamAdvisor {

    private final RetrievalAugmentationAdvisor delegate;

    public RetrievalGateAdvisor(RetrievalAugmentationAdvisor delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (shouldSkip(request)) {
            return chain.nextCall(request);
        }
        return delegate.adviseCall(request, chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (shouldSkip(request)) {
            return chain.nextStream(request);
        }
        return delegate.adviseStream(request, chain);
    }

    private static boolean shouldSkip(ChatClientRequest request) {
        return request.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx
            && ctx.isSkipRetrieval();
    }

    @Override
    public String getName() {
        return "RetrievalGateAdvisor";
    }

    /** 链序表槽位不变（11.2 v2.13）：承接原 RetrievalAugmentationAdvisor 的 500 位 */
    @Override
    public int getOrder() {
        return 500;
    }
}
