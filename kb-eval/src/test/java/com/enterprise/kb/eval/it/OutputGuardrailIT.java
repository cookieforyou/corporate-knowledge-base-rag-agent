package com.enterprise.kb.eval.it;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.eval.it.stub.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 输出黑名单护栏（簇⑥ D3）：同步整段替换 + 流式增量放行截断（v2.109 语义——
 * 命中即吞块，REPLACE 追回经 ctx 标记由 SSE 层消费）+ 洁净输出直通。
 * 黑名单测试词表经基类属性注入（竞品Alpha / 违禁词Beta）。
 */
class OutputGuardrailIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-GUARD";
    private static final String SAFE_RESPONSE = "抱歉，由于合规要求，无法提供该信息。";

    @Autowired private RagChatService ragChatService;
    @Autowired private StubChatModel stub;
    @Autowired private VectorStore vectorStore;

    @BeforeEach
    void setUp() {
        stub.reset();
        // answer 由各用例 setDefaultAnswer 决定——Supplier 绑定动态读取
        stub.setResponseRouter(knowledgeRouter(stub::currentDefaultAnswer));
        vectorStore.add(List.of(doc("guard-doc", "公司产品线说明与推荐清单", TENANT)));
    }

    @Test
    void blacklist_syncReplaced() {
        stub.setDefaultAnswer("推荐使用竞品Alpha产品，体验更好");

        String answer = ragChatService.chatRag("推荐产品", sessionId(), ctx(TENANT, "U-G"));

        assertThat(answer).isEqualTo(SAFE_RESPONSE);
    }

    @Test
    void blacklist_streamReplaced() {
        stub.setDefaultAnswer("推荐使用竞品Alpha产品，体验更好");

        RetrievalContext ctx = ctx(TENANT, "U-G");
        Flux<String> stream = ragChatService.chatStreamRag("推荐产品", sessionId(), ctx);
        String joined = String.join("", stream.collectList().block());

        // 流式增量放行（v2.109）：命中即吞块截断——违禁词及后续文本永不出流，
        // 服务层 Flux 不携带替换话术；REPLACE 追回经 ctx 标记由 SSE 层消费
        assertThat(joined).doesNotContain("竞品Alpha");
        assertThat(ctx.isOutputReplaced()).isTrue();
        assertThat(ctx.getOutputReplacement()).isEqualTo(SAFE_RESPONSE);
    }

    @Test
    void cleanOutput_passesThrough() {
        stub.setDefaultAnswer("公司使用自研平台");

        String answer = ragChatService.chatRag("公司平台", sessionId(), ctx(TENANT, "U-G"));

        assertThat(answer).isEqualTo("公司使用自研平台");
    }
}
