package com.enterprise.kb.ai.retriever;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * 查询改写捕获装饰器（设计文档 11.6，任务 3.12）
 *
 * <p>{@link QueryTransformer} 装饰器：委托真实改写器（簇④ A5 起为历史感知的
 * CompressionQueryTransformer 形态）执行改写，并把改写结果写回请求级
 * {@link RetrievalContext}——供
 * AuditTraceAdvisor 落库 kb_audit_log.rewritten_query（Bad Case 回溯需要看到
 * 模型实际检索用的 query 形态）。
 *
 * <p>机制依据（源码核验）：RetrievalAugmentationAdvisor 将 advisor 参数复制进
 * Query.context 后交给 transformer——本装饰器经 {@code query.context()} 读取
 * 同一 RetrievalContext 实例，改写文本经实例字段旁路传出，不改框架契约。
 * 上下文无 RetrievalContext（kb-eval 等非 Web 入口）时静默跳过。
 *
 * <p>仅包装主链路装配（RetrievalConfig）；检索调试台（2.14）直注的原始
 * rewriteQueryTransformer Bean（同一 Compression 形态）不受影响。
 *
 * <p>5.4 收窄版扩展：ctx.rewrittenQuery 已被 QueryRoutingAdvisor(440) 预写入时，
 * 跳过 delegate 的 LLM 调用直接复用（分类与改写合并为一次调用）。
 */
public class RewriteCapturingQueryTransformer implements QueryTransformer {

    private final QueryTransformer delegate;

    public RewriteCapturingQueryTransformer(QueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        // 5.4 收窄版：QueryRoutingAdvisor(440) 已把分类+消解合并产出的改写文本预写入
        // ctx——直接复用，跳过 delegate 的 LLM 调用（知识问零新增延迟的关键）
        if (query.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx
            && ctx.getRewrittenQuery() != null && !ctx.getRewrittenQuery().isBlank()) {
            // Query 构造必须透传 context：下游检索器经 Query.context 读 RetrievalContext
            return new Query(ctx.getRewrittenQuery(), query.history(), query.context());
        }
        Query rewritten = delegate.transform(query);
        if (query.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx
            && rewritten != null) {
            ctx.setRewrittenQuery(rewritten.text());
        }
        return rewritten;
    }
}
