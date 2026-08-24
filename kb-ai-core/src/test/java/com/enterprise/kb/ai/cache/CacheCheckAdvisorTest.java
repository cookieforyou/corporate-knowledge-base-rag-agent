package com.enterprise.kb.ai.cache;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 语义缓存检查 Advisor 测试（Phase 5 簇③ 5.6 批2）——资格判定五闸 / 命中重放
 * （回答 + 溯源回填 + 上下文透传）/ 未命中写入门槛（证据非空 + 流完成）/
 * fail-open 直通 / trace 载荷写入-重放往返保真。
 */
class CacheCheckAdvisorTest {

    private static final String TENANT = "t-1";
    private static final float[] VECTOR = {0.1f, 0.2f, 0.3f};

    private SemanticCacheService cacheService;
    private EmbeddingModel embeddingModel;
    private CallAdvisorChain callChain;
    private StreamAdvisorChain streamChain;
    private JsonMapper jsonMapper;
    private CacheCheckAdvisor advisor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cacheService = mock(SemanticCacheService.class);
        embeddingModel = mock(EmbeddingModel.class);
        callChain = mock(CallAdvisorChain.class);
        streamChain = mock(StreamAdvisorChain.class);
        jsonMapper = new JsonMapper();
        ObjectProvider<ObservationRegistry> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        when(cacheService.isAvailable()).thenReturn(true);
        when(embeddingModel.embed(anyString())).thenReturn(VECTOR);
        // 写执行器直跑形态（同 Runnable::run 纪律）：异步写入同步可验证
        advisor = new CacheCheckAdvisor(cacheService, embeddingModel, jsonMapper,
            (AsyncTaskExecutor) Runnable::run, registryProvider);
    }

    // ── 构造助手 ──

    private static RetrievalContext ctx() {
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(TENANT);
        return ctx;
    }

    private static ChatClientRequest request(RetrievalContext ctx, Message... messages) {
        Map<String, Object> context = new HashMap<>();
        if (ctx != null) {
            context.put(RetrievalContext.CONTEXT_KEY, ctx);
        }
        List<Message> list = messages.length == 0
            ? List.of(new UserMessage("什么是增值税发票？"))
            : List.of(messages);
        return new ChatClientRequest(new Prompt(list), context);
    }

    private static ChatClientResponse response(String text) {
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))
            .build();
    }

    private static Document evidenceDoc(String docId) {
        return Document.builder()
            .text("证据正文")
            .score(0.88)
            .metadata(Map.of("chunk_id", "c-1", "doc_id", docId, "file_name", "手册.pdf",
                "page_num", 2, "rerank_score", 0.88))
            .build();
    }

    private SemanticCacheService.CacheHit hit(String answer, String traceJson) {
        return new SemanticCacheService.CacheHit("什么是增值税发票？", answer, traceJson,
            List.of("doc-9"), 0.97);
    }

    // ── 资格判定 ──

    @Test
    void noContextProceedsWithoutEmbeddingOrLookup() {
        ChatClientRequest req = request(null);
        ChatClientResponse expected = response("直答");
        when(callChain.nextCall(req)).thenReturn(expected);

        assertThat(advisor.adviseCall(req, callChain)).isSameAs(expected);
        verifyNoInteractions(embeddingModel);
        verify(cacheService, never()).lookup(anyString(), any());
    }

    @Test
    void skipRetrievalProceedsWithoutLookup() {
        RetrievalContext ctx = ctx();
        ctx.setSkipRetrieval(true);
        ChatClientRequest req = request(ctx);
        when(callChain.nextCall(req)).thenReturn(response("闲聊直答"));

        advisor.adviseCall(req, callChain);

        verifyNoInteractions(embeddingModel);
        verify(cacheService, never()).lookup(anyString(), any());
    }

    @Test
    void multiTurnProceedsWithoutLookup() {
        ChatClientRequest req = request(ctx(),
            new UserMessage("第一个问题"), new AssistantMessage("历史回答"),
            new UserMessage("那税率是多少？"));
        when(callChain.nextCall(req)).thenReturn(response("回答"));

        advisor.adviseCall(req, callChain);

        verifyNoInteractions(embeddingModel);
        verify(cacheService, never()).lookup(anyString(), any());
    }

    @Test
    void unavailableServiceProceedsWithoutEmbedding() {
        when(cacheService.isAvailable()).thenReturn(false);
        ChatClientRequest req = request(ctx());
        when(callChain.nextCall(req)).thenReturn(response("回答"));

        advisor.adviseCall(req, callChain);

        verifyNoInteractions(embeddingModel);
    }

    @Test
    void blankQuestionProceedsWithoutLookup() {
        ChatClientRequest req = request(ctx(), new UserMessage("   "));
        when(callChain.nextCall(req)).thenReturn(response("回答"));

        advisor.adviseCall(req, callChain);

        verifyNoInteractions(embeddingModel);
    }

    // ── 命中重放 ──

    @Test
    void callHitReturnsCachedAnswerReplaysTraceAndBypassesChain() {
        RetrievalContext ctx = ctx();
        ChatClientRequest req = request(ctx);
        String traceJson = jsonMapper.writeValueAsString(List.of(new CacheTracePayload("final",
            List.of(new CacheTracePayload.CachedChunk("证据正文", 0.88,
                Map.of("chunk_id", "c-1", "doc_id", "doc-9", "file_name", "手册.pdf",
                    "page_num", 2, "rerank_score", 0.88))), 21L)));
        when(cacheService.lookup(eq(TENANT), any(float[].class)))
            .thenReturn(Optional.of(hit("缓存回答 [ref-1]", traceJson)));

        ChatClientResponse result = advisor.adviseCall(req, callChain);

        assertThat(result.chatResponse().getResult().getOutput().getText()).isEqualTo("缓存回答 [ref-1]");
        // 请求上下文透传（外层记忆/输出护栏/溯源 after() 消费面不破；
        // builder 内部防御性拷贝，断言内容同一实例可达）
        assertThat(result.context().get(RetrievalContext.CONTEXT_KEY)).isSameAs(ctx);
        // 溯源回填：Controller 流末 TRACE / 审计 / 归档同形消费
        assertThat(ctx.getTraceSummary()).hasSize(1);
        RetrievalContext.TraceEntry replayed = ctx.getTraceSummary().get(0);
        assertThat(replayed.source()).isEqualTo("final");
        assertThat(replayed.latencyMs()).isEqualTo(21L);
        assertThat(replayed.documents().get(0).getText()).isEqualTo("证据正文");
        assertThat(replayed.documents().get(0).getScore()).isEqualTo(0.88);
        assertThat(replayed.documents().get(0).getMetadata())
            .containsEntry("chunk_id", "c-1").containsEntry("doc_id", "doc-9");
        // 内层链零触达：检索/重排/生成全套旁路
        verify(callChain, never()).nextCall(any());
    }

    @Test
    void streamHitEmitsSingleChunkWithCachedAnswer() {
        RetrievalContext ctx = ctx();
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class)))
            .thenReturn(Optional.of(hit("缓存回答 [ref-1]", "")));

        List<ChatClientResponse> results = advisor.adviseStream(req, streamChain)
            .collectList().block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).chatResponse().getResult().getOutput().getText())
            .isEqualTo("缓存回答 [ref-1]");
        verify(streamChain, never()).nextStream(any());
    }

    @Test
    void hitWithUnparseableTraceStillReturnsAnswerWithEmptyTrace() {
        RetrievalContext ctx = ctx();
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class)))
            .thenReturn(Optional.of(hit("缓存回答", "不是合法 JSON")));

        ChatClientResponse result = advisor.adviseCall(req, callChain);

        assertThat(result.chatResponse().getResult().getOutput().getText()).isEqualTo("缓存回答");
        assertThat(ctx.getTraceSummary()).isEmpty();
    }

    // ── 未命中写入 ──

    @Test
    void callMissWritesEntryWithEvidenceDocIdsAndReusedVector() {
        RetrievalContext ctx = ctx();
        ctx.addTraceEntry("vector", List.of(evidenceDoc("doc-9")), 30L);
        ctx.addTraceEntry("final", List.of(evidenceDoc("doc-9")), 21L);
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.empty());
        when(callChain.nextCall(req)).thenReturn(response("新生成的回答 [ref-1]"));

        advisor.adviseCall(req, callChain);

        ArgumentCaptor<SemanticCacheEntry> entry = ArgumentCaptor.forClass(SemanticCacheEntry.class);
        verify(cacheService).put(eq(TENANT), entry.capture(), eq(VECTOR));
        assertThat(entry.getValue().question()).isEqualTo("什么是增值税发票？");
        assertThat(entry.getValue().answer()).isEqualTo("新生成的回答 [ref-1]");
        assertThat(entry.getValue().docIds()).containsExactly("doc-9");
        // trace 载荷覆盖双路 + final 全序列
        assertThat(entry.getValue().traceJson()).contains("\"final\"").contains("\"vector\"");
    }

    @Test
    void missWithEmptyEvidenceDoesNotWrite() {
        RetrievalContext ctx = ctx(); // 无 trace 条目 = 空证据（拒答路径）
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.empty());
        when(callChain.nextCall(req)).thenReturn(response("抱歉，知识库中未找到相关证据。"));

        advisor.adviseCall(req, callChain);

        verify(cacheService, never()).put(anyString(), any(), any());
    }

    @Test
    void streamMissAggregatesAnswerAndWritesOnComplete() {
        RetrievalContext ctx = ctx();
        ctx.addTraceEntry("final", List.of(evidenceDoc("doc-9")), 21L);
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.empty());
        when(streamChain.nextStream(req)).thenReturn(Flux.just(response("分段一"), response("分段二")));

        List<ChatClientResponse> results = advisor.adviseStream(req, streamChain)
            .collectList().block();
        assertThat(results).hasSize(2);

        ArgumentCaptor<SemanticCacheEntry> entry = ArgumentCaptor.forClass(SemanticCacheEntry.class);
        verify(cacheService).put(eq(TENANT), entry.capture(), eq(VECTOR));
        assertThat(entry.getValue().answer()).isEqualTo("分段一分段二");
    }

    @Test
    void streamErrorDoesNotWrite() {
        RetrievalContext ctx = ctx();
        ctx.addTraceEntry("final", List.of(evidenceDoc("doc-9")), 21L);
        ChatClientRequest req = request(ctx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.empty());
        when(streamChain.nextStream(req)).thenReturn(Flux.error(new RuntimeException("生成中断")));

        assertThatThrownBy(() -> advisor.adviseStream(req, streamChain).collectList().block())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("生成中断");

        verify(cacheService, never()).put(anyString(), any(), any());
    }

    // ── fail-open ──

    @Test
    void embeddingFailureFailsOpenToFullChain() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding 服务不可达"));
        ChatClientRequest req = request(ctx());
        ChatClientResponse expected = response("完整链路回答");
        when(callChain.nextCall(req)).thenReturn(expected);

        assertThat(advisor.adviseCall(req, callChain)).isSameAs(expected);
        verify(cacheService, never()).lookup(anyString(), any());
    }

    // ── trace 载荷写入-重放往返保真 ──

    @Test
    void tracePayloadSurvivesWriteThenReplayRoundTrip() {
        // 写入侧：未命中请求产出含元数据全量的条目
        RetrievalContext writeCtx = ctx();
        writeCtx.addTraceEntry("final", List.of(evidenceDoc("doc-9")), 21L);
        ChatClientRequest writeReq = request(writeCtx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.empty());
        when(callChain.nextCall(writeReq)).thenReturn(response("回答 [ref-1]"));
        advisor.adviseCall(writeReq, callChain);
        ArgumentCaptor<SemanticCacheEntry> entry = ArgumentCaptor.forClass(SemanticCacheEntry.class);
        verify(cacheService).put(eq(TENANT), entry.capture(), any(float[].class));

        // 重放侧：同一条目命中后溯源保真回填
        RetrievalContext replayCtx = ctx();
        ChatClientRequest replayReq = request(replayCtx);
        when(cacheService.lookup(eq(TENANT), any(float[].class))).thenReturn(Optional.of(
            new SemanticCacheService.CacheHit(entry.getValue().question(), entry.getValue().answer(),
                entry.getValue().traceJson(), entry.getValue().docIds(), 0.96)));
        advisor.adviseCall(replayReq, callChain);

        RetrievalContext.TraceEntry replayed = replayCtx.getTraceSummary().get(0);
        Document doc = replayed.documents().get(0);
        assertThat(doc.getText()).isEqualTo("证据正文");
        assertThat(doc.getScore()).isEqualTo(0.88);
        assertThat(doc.getMetadata())
            .containsEntry("doc_id", "doc-9")
            .containsEntry("file_name", "手册.pdf")
            .containsEntry("rerank_score", 0.88);
    }
}
