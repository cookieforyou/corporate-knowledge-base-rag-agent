package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全链路探针（簇① A1，2026-08-11）——度量真实 advisor 链的检索产出
 *
 * <p>与 {@link HybridRetrievalProbe} 的分工：hybrid 探针直调检索器本体，度量
 * 「检索本征质量」（无改写、无扩展、无重排）；本探针走 eval {@code chatClient}
 * 生产等价链（改写 → [多查询扩展] → 双路检索 → RRF → 重排）全链，经
 * {@link RetrievalContext} 参数链取重排后 {@code source=final} trace 条目——
 * 与生产 SSE TRACE / [ref-N] 序列同源。是度量查询改写/扩展等**前置检索组件**
 * 收益的唯一探针（hybrid 探针结构性绕过 advisor 链，A/B 对其不可见）。
 *
 * <p>代价：每次探针触发含生成模型的全链调用（答案丢弃、只取 trace），显著慢于
 * hybrid 探针；order=50 高于 hybrid(0)，{@code eval.probe=auto} 默认选择不变，
 * 须显式 {@code eval.probe=chain} 启用。
 *
 * <p>fail-closed 适配：{@code HybridDocumentRetriever} 对「有 ctx 无租户」返回
 * 空结果（3.9/3.10 租户隔离），故本探针必须设置 {@code eval.chain-probe.tenant-id}
 * （语料所属租户，如 tenant_001）；未设置时探针调用即抛错，避免静默产出零指标。
 */
@Component("chainRetrievalProbe")
public class ChainRetrievalProbe implements RetrievalProbe {

    private final ChatClient chatClient;
    private final String tenantId;

    public ChainRetrievalProbe(@Qualifier("chatClient") ChatClient chatClient,
                               @Value("${eval.chain-probe.tenant-id:}") String tenantId) {
        this.chatClient = chatClient;
        this.tenantId = tenantId;
    }

    @Override
    public List<ProbeHit> probe(String query, int topK) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                "eval.probe=chain 需设置 eval.chain-probe.tenant-id（检索器对有 ctx 无租户 fail-closed 返回空）");
        }
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(tenantId);
        chatClient.prompt().user(query)
            .advisors(spec -> spec.param(RetrievalContext.CONTEXT_KEY, ctx))
            .call()
            .content();
        return ctx.getTraceSummary().stream()
            .filter(e -> "final".equals(e.source()))
            .findFirst()
            .map(RetrievalContext.TraceEntry::documents)
            .orElse(List.of())
            .stream()
            .limit(topK)
            .map(ChainRetrievalProbe::toHit)
            .toList();
    }

    private static ProbeHit toHit(Document d) {
        Object chunkId = d.getMetadata().getOrDefault("chunk_id", d.getId());
        double score = d.getScore() != null ? d.getScore() : 0.0;
        return new ProbeHit(String.valueOf(chunkId), d.getText(), score);
    }

    @Override
    public String name() {
        return "chain";
    }

    @Override
    public int getOrder() {
        return 50;
    }
}
