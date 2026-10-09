package com.enterprise.kb.ai.retriever;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RetrievalContext 读入口族单测（10.2.1，2026-10-04 收敛）——四条入参形态 + 静默降级契约。
 *
 * <p>本族方法的价值在于**全仓 instanceof 判据只此一处**：原各 Advisor / 检索器 / 工具类各自
 * 复刻 `context().get(CONTEXT_KEY) instanceof RetrievalContext`，形态漂移（漏判 null、漏判类型）
 * 只会以「静默无租户过滤」的形式显现。故此处把「命中 / 缺失 / 类型不符 / null」四种结果逐条钉死，
 * 后续任何调用点改用本族方法都受同一份契约约束。
 */
class RetrievalContextTest {

    private static Map<String, Object> contextOf(RetrievalContext ctx) {
        Map<String, Object> context = new HashMap<>();
        context.put(RetrievalContext.CONTEXT_KEY, ctx);
        return context;
    }

    @Test
    void fromQuery_returnsContext_whenPresent() {
        RetrievalContext ctx = new RetrievalContext();
        Query query = new Query("问题", List.of(), contextOf(ctx));

        assertThat(RetrievalContext.from(query)).isSameAs(ctx);
    }

    @Test
    void fromChatClientRequest_andResponse_returnSameInstance() {
        RetrievalContext ctx = new RetrievalContext();
        ChatClientRequest request = new ChatClientRequest(
            new Prompt(List.of(new UserMessage("问题"))), contextOf(ctx));
        ChatClientResponse response = ChatClientResponse.builder()
            .context(contextOf(ctx))
            .build();

        assertThat(RetrievalContext.from(request)).isSameAs(ctx);
        // 请求/响应两侧同源透传：Advisor before/after 取到的是同一实例（参数链语义）
        assertThat(RetrievalContext.from(response)).isSameAs(ctx);
    }

    @Test
    void fromMap_returnsContext_whenKeyPresent() {
        RetrievalContext ctx = new RetrievalContext();

        assertThat(RetrievalContext.from(contextOf(ctx))).isSameAs(ctx);
    }

    @Test
    void fromObject_acceptsRawParameterValue() {
        // toolContext 取值形态（Object 静态类型）：命中即返回，无需调用方自行 instanceof
        RetrievalContext ctx = new RetrievalContext();
        Object value = ctx;

        assertThat(RetrievalContext.from(value)).isSameAs(ctx);
    }

    @Test
    void missingContext_yieldsNull_onEveryEntryPoint() {
        // 非 Web 入口（kb-eval）与降级路径：全部返回 null，调用方据此走无租户过滤/无 trace 分支
        assertThat(RetrievalContext.from(new Query("问题"))).isNull();
        assertThat(RetrievalContext.from(new Query("问题", List.of(), Map.of()))).isNull();
        assertThat(RetrievalContext.from(
            new ChatClientRequest(new Prompt(List.of(new UserMessage("问题"))), Map.of()))).isNull();
        assertThat(RetrievalContext.from(ChatClientResponse.builder().context(Map.of()).build())).isNull();
        assertThat(RetrievalContext.from(Map.of())).isNull();
        assertThat(RetrievalContext.from((Object) null)).isNull();
    }

    @Test
    void nullContextMap_yieldsNull_withoutThrowing() {
        // Map 形态显式容错（原各调用点的 `context != null` 判据收敛于此）
        assertThat(RetrievalContext.from((Map<String, Object>) null)).isNull();
    }

    @Test
    void wrongValueType_yieldsNull_withoutThrowing() {
        // 宽口径入口的契约：非 RetrievalContext 值一律 null（键被别的组件占用/写错类型时不炸链路）
        assertThat(RetrievalContext.from(Map.of(RetrievalContext.CONTEXT_KEY, "not-a-context"))).isNull();
        assertThat(RetrievalContext.from((Object) "not-a-context")).isNull();
        assertThat(RetrievalContext.from(Map.of(RetrievalContext.CONTEXT_KEY, Map.of()))).isNull();
    }
}
