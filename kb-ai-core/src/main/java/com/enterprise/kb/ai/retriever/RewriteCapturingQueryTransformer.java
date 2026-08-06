package com.enterprise.kb.ai.retriever;

import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * 查询改写捕获装饰器（设计文档 11.6，任务 3.12）
 *
 * <p>{@link QueryTransformer} 装饰器：委托真实改写器（RewriteQueryTransformer）
 * 执行改写，并把改写结果写回请求级 {@link RetrievalContext}——供
 * AuditTraceAdvisor 落库 kb_audit_log.rewritten_query（Bad Case 回溯需要看到
 * 模型实际检索用的 query 形态）。
 *
 * <p>机制依据（源码核验）：RetrievalAugmentationAdvisor 将 advisor 参数复制进
 * Query.context 后交给 transformer——本装饰器经 {@code query.context()} 读取
 * 同一 RetrievalContext 实例，改写文本经实例字段旁路传出，不改框架契约。
 * 上下文无 RetrievalContext（kb-eval 等非 Web 入口）时静默跳过。
 *
 * <p>仅包装主链路装配（RetrievalConfig）；检索调试台（2.14）直注的原始
 * RewriteQueryTransformer Bean 不受影响。
 */
public class RewriteCapturingQueryTransformer implements QueryTransformer {

    private final QueryTransformer delegate;

    public RewriteCapturingQueryTransformer(QueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        Query rewritten = delegate.transform(query);
        if (query.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx
            && rewritten != null) {
            ctx.setRewrittenQuery(rewritten.text());
        }
        return rewritten;
    }
}
