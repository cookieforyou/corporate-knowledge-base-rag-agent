package com.enterprise.kb.ai.retriever;

import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 改写捕获装饰器测试（3.12）——改写文本写回 RetrievalContext 供审计落库
 */
class RewriteCapturingQueryTransformerTest {

    @Test
    void capturesRewrittenTextIntoRetrievalContext() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        RetrievalContext ctx = new RetrievalContext();
        Query original = new Query("它呢", List.of(),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));
        when(delegate.transform(original)).thenReturn(
            new Query("增值税发票的税率是多少", List.of(), Map.of()));

        Query result = new RewriteCapturingQueryTransformer(delegate).transform(original);

        assertThat(result.text()).isEqualTo("增值税发票的税率是多少");
        assertThat(ctx.getRewrittenQuery()).isEqualTo("增值税发票的税率是多少");
    }

    @Test
    void withoutRetrievalContextSilentlySkips() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        Query original = new Query("问题");
        when(delegate.transform(original)).thenReturn(new Query("改写"));

        Query result = new RewriteCapturingQueryTransformer(delegate).transform(original);

        assertThat(result.text()).isEqualTo("改写");
    }

    // ── 5.4 收窄版：分类器预改写复用 ──

    @Test
    void preRewrittenQuerySkipsDelegateLlmCall() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        RetrievalContext ctx = new RetrievalContext();
        ctx.setRewrittenQuery("分类器预改写的完整查询");
        Query original = new Query("它的税率呢", List.of(),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));

        Query result = new RewriteCapturingQueryTransformer(delegate).transform(original);

        assertThat(result.text()).isEqualTo("分类器预改写的完整查询");
        verifyNoInteractions(delegate);
    }

    @Test
    void preRewrittenQueryPreservesContextForRetrievers() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        RetrievalContext ctx = new RetrievalContext();
        ctx.setRewrittenQuery("预改写");
        Query original = new Query("原问题", List.of(),
            Map.of(RetrievalContext.CONTEXT_KEY, ctx));

        Query result = new RewriteCapturingQueryTransformer(delegate).transform(original);

        // 检索器经 Query.context 读 RetrievalContext——context 必须原样透传
        assertThat(result.context().get(RetrievalContext.CONTEXT_KEY)).isSameAs(ctx);
    }
}
