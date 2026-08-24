package com.enterprise.kb.ai.cache;

import com.enterprise.kb.ai.retriever.RetrievalContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存检查 Advisor（Phase 5 簇③ 5.6 批2）—— Order 460：命中短路重放 / 未命中流末写入
 *
 * <p><b>槽位语义（11.2 链序表）</b>：QueryRouting(440)/RetrievalTrace(450) 之后、
 * RetrievalGate(500) 之前——
 * <ul>
 *   <li>路由先行：CHITCHAT 置 {@code skipRetrieval} 的免检索直答不查缓存
 *       （闲聊不入缓存，结构性免除误命中）；</li>
 *   <li>记忆(400)已跑：历史已并入 prompt，单轮判定（USER 消息数 == 1）可靠；</li>
 *   <li>命中旁路 Gate(500)/改写/双路检索/RRF/重排/生成全套，直接重放缓存回答与溯源载荷。</li>
 * </ul>
 *
 * <p><b>命中重放</b>：回答原样下发（流式路径为单个 TOKEN 帧，前端追加协议兼容）；
 * 溯源载荷反序列化回填 {@link RetrievalContext#addTraceEntry}——Controller 流末
 * TRACE 事件、审计落库、会话归档与正常检索同形消费（[ref-N] 锚定不破）。重放响应
 * 携带请求上下文（CONVERSATION_ID / RetrievalContext），外层记忆 / 输出护栏 /
 * 溯源 advisor 的 after() 同正常链路消费。
 *
 * <p><b>写入门槛（误命中防线，与批1 定案一致）</b>：仅当全部满足才写入——
 * ① rag 链（结构性：本 Advisor 只挂 ragAgentChatClient，tool/eval 链零触达）；
 * ② 单轮（多轮上下文依赖不入缓存）；③ 非闲聊路由；④ 流正常完成（SUCCESS
 * 操作语义：拒绝/异常走 doOnError / 异常路径不达写入）；⑤ final 重排证据非空
 * （空证据拒答不缓存）。写入复用查找期嵌入向量——每请求单次嵌入调用零重复开销。
 *
 * <p><b>直实现 CallAdvisor + StreamAdvisor 而非 BaseAdvisor</b>（同
 * {@code RetrievalGateAdvisor} 形态）：BaseAdvisor 模板无法表达「短路内层链」语义。
 *
 * <p><b>缺省关</b>：{@code @ConditionalOnProperty(rag.cache.enabled)} 条件装配，
 * 消费方（RagAgentChatClientConfig）经 {@link ObjectProvider} 容忍缺位——
 * 关闭态链形态与批2 前完全一致（行为零变化纪律）。
 *
 * <p><b>fail-open 纪律</b>：嵌入 / 查找 / 写入任一环节故障 → warn + 直通
 * （缓存是优化件不是防线，故障不得传导为对话链失败），与 {@link SemanticCacheService} 同款。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.cache", name = "enabled", havingValue = "true")
public class CacheCheckAdvisor implements CallAdvisor, StreamAdvisor {

    /** 观测 span 名（簇①观测地基族；对齐 {@code kb.rerank} 命名） */
    static final String OBSERVATION_NAME = "kb.cache.semantic";

    /** 重排后最终序列 trace 源标识（与检索/重排链路写入侧同值） */
    private static final String SOURCE_FINAL = "final";

    /** 证据文档的文档标识元数据键（失效反查依赖，与 ETL vectorMetadata 契约同源） */
    private static final String META_DOC_ID = "doc_id";

    private final SemanticCacheService cacheService;
    private final EmbeddingModel embeddingModel;
    private final JsonMapper jsonMapper;
    private final AsyncTaskExecutor writeExecutor;
    private final ObservationRegistry observationRegistry;

    /**
     * 装配构造器：写执行器复用 auditExecutor（同款虚拟线程异步旁路写语义——
     * 缓存写入与审计落库同为「每请求一次、失败不阻断」的旁路持久化）。
     */
    public CacheCheckAdvisor(SemanticCacheService cacheService,
                             EmbeddingModel embeddingModel,
                             JsonMapper jsonMapper,
                             @Qualifier("auditExecutor") AsyncTaskExecutor writeExecutor,
                             ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.cacheService = cacheService;
        this.embeddingModel = embeddingModel;
        this.jsonMapper = jsonMapper;
        this.writeExecutor = writeExecutor;
        this.observationRegistry = observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        RetrievalContext ctx = ctxOf(request);
        if (!eligible(request, ctx)) {
            return chain.nextCall(request);
        }
        LookupOutcome outcome = lookup(request, ctx);
        if (outcome == null) {
            return chain.nextCall(request);
        }
        if (outcome.hit() != null) {
            replayTrace(ctx, outcome.hit());
            return cachedResponse(request, outcome.hit());
        }
        ChatClientResponse response = chain.nextCall(request);
        writeIfEligible(request, ctx, outcome.vector(), textOf(response));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        RetrievalContext ctx = ctxOf(request);
        if (!eligible(request, ctx)) {
            return chain.nextStream(request);
        }
        LookupOutcome outcome = lookup(request, ctx);
        if (outcome == null) {
            return chain.nextStream(request);
        }
        if (outcome.hit() != null) {
            replayTrace(ctx, outcome.hit());
            return Flux.just(cachedResponse(request, outcome.hit()));
        }
        StringBuilder answerBuffer = new StringBuilder();
        return chain.nextStream(request)
            .doOnNext(response -> {
                String text = textOf(response);
                if (text != null) {
                    answerBuffer.append(text);
                }
            })
            .doOnComplete(() -> writeIfEligible(request, ctx, outcome.vector(), answerBuffer.toString()));
    }

    // ── 资格判定 ──

    /**
     * 缓存资格（全部满足才查/写）：能力可用 + 租户在场 + 非闲聊路由 + 单轮 + 问句非空。
     * 单轮判定依据：记忆 advisor(400) 已将历史并入 prompt，USER 消息数 >1 即多轮——
     * 上下文依赖问句不入缓存（同形问句跨上下文语义不同，误命中防线）。
     */
    private boolean eligible(ChatClientRequest request, RetrievalContext ctx) {
        if (ctx == null || !cacheService.isAvailable()) {
            return false;
        }
        if (ctx.getTenantId() == null || ctx.getTenantId().isBlank()) {
            return false;
        }
        if (ctx.isSkipRetrieval()) {
            return false;
        }
        if (countUserMessages(request) > 1) {
            return false;
        }
        return questionOf(request) != null;
    }

    private static RetrievalContext ctxOf(ChatClientRequest request) {
        return request.context().get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext ctx
            ? ctx : null;
    }

    private static long countUserMessages(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
            .filter(m -> m.getMessageType() == MessageType.USER)
            .count();
    }

    /** 当前轮问句（末条 USER 消息，InputSanitize 已掩码形态——存取两侧同形一致） */
    private static String questionOf(ChatClientRequest request) {
        var userMessage = request.prompt().getUserMessage();
        if (userMessage == null || userMessage.getText() == null) {
            return null;
        }
        String text = userMessage.getText().trim();
        return text.isEmpty() ? null : text;
    }

    // ── 查找（观测包裹，fail-open） ──

    /** 嵌入 + KNN 查找；任一环节故障返回 null（直通完整链路），观测记 error */
    private LookupOutcome lookup(ChatClientRequest request, RetrievalContext ctx) {
        Observation observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
            .contextualName("semantic cache lookup")
            .highCardinalityKeyValue("cache.tenant", ctx.getTenantId());
        observation.start();
        try {
            float[] vector = embeddingModel.embed(questionOf(request));
            Optional<SemanticCacheService.CacheHit> hit = cacheService.lookup(ctx.getTenantId(), vector);
            observation.lowCardinalityKeyValue("cache.outcome", hit.isPresent() ? "hit" : "miss");
            return new LookupOutcome(vector, hit.orElse(null));
        } catch (Exception e) {
            observation.error(e);
            log.warn("语义缓存查找失败，fail-open 直通完整链路：{}", e.getMessage());
            return null;
        } finally {
            observation.stop();
        }
    }

    // ── 命中重放 ──

    /** 命中响应：缓存回答单帧下发 + 请求上下文原样透传（外层 after() 同正常链路消费） */
    private static ChatClientResponse cachedResponse(ChatClientRequest request,
                                                     SemanticCacheService.CacheHit hit) {
        return ChatClientResponse.builder()
            .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(hit.answer())))))
            .context(request.context())
            .build();
    }

    /** 溯源载荷回填：缓存 traceJson → RetrievalContext trace 序列（解析失败降级空溯源，回答仍有效） */
    private void replayTrace(RetrievalContext ctx, SemanticCacheService.CacheHit hit) {
        if (hit.traceJson() == null || hit.traceJson().isBlank()) {
            return;
        }
        try {
            CacheTracePayload[] payloads = jsonMapper.readValue(hit.traceJson(), CacheTracePayload[].class);
            for (CacheTracePayload payload : payloads) {
                List<Document> documents = payload.chunks() == null ? List.of()
                    : payload.chunks().stream()
                        .map(c -> Document.builder()
                            .text(c.text() == null ? "" : c.text())
                            .metadata(c.metadata() == null ? Map.of() : c.metadata())
                            .score(c.score())
                            .build())
                        .toList();
                ctx.addTraceEntry(payload.source(), documents, payload.latencyMs());
            }
        } catch (Exception e) {
            log.warn("缓存溯源载荷解析失败，命中回答仍有效（溯源降级为空）：{}", e.getMessage());
        }
    }

    // ── 未命中写入（门槛把关 + 异步旁路） ──

    /**
     * 写入门槛（批1 定案）：回答非空 ∧ final 重排证据非空（空证据拒答不缓存）。
     * trace 载荷序列化失败即放弃写入（保守：宁可下次重新生成，不存无溯源条目）。
     * 实际落 Redis 经 writeExecutor 虚拟线程异步，不占响应路径延迟。
     */
    private void writeIfEligible(ChatClientRequest request, RetrievalContext ctx,
                                 float[] vector, String answer) {
        try {
            if (answer == null || answer.isBlank()) {
                return;
            }
            List<RetrievalContext.TraceEntry> entries = ctx.getTraceSummary();
            boolean hasEvidence = entries.stream()
                .anyMatch(e -> SOURCE_FINAL.equals(e.source()) && !e.documents().isEmpty());
            if (!hasEvidence) {
                return;
            }
            String question = questionOf(request);
            if (question == null) {
                return;
            }
            List<String> docIds = entries.stream()
                .filter(e -> SOURCE_FINAL.equals(e.source()))
                .flatMap(e -> e.documents().stream())
                .map(d -> asString(d.getMetadata().get(META_DOC_ID)))
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
            String traceJson = jsonMapper.writeValueAsString(projectTrace(entries));
            SemanticCacheEntry entry = new SemanticCacheEntry(question, answer, traceJson, docIds, Instant.now());
            writeExecutor.execute(() -> cacheService.put(ctx.getTenantId(), entry, vector));
        } catch (Exception e) {
            log.warn("语义缓存写入失败，跳过（不影响本次回答）：{}", e.getMessage());
        }
    }

    /** trace 序列 → 缓存投影（只存 TRACE/审计消费面字段，见 {@link CacheTracePayload}） */
    private static List<CacheTracePayload> projectTrace(List<RetrievalContext.TraceEntry> entries) {
        return entries.stream()
            .map(e -> new CacheTracePayload(e.source(),
                e.documents().stream()
                    .map(d -> new CacheTracePayload.CachedChunk(d.getText(), d.getScore(), d.getMetadata()))
                    .toList(),
                e.latencyMs()))
            .toList();
    }

    private static String textOf(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return null;
        }
        return response.chatResponse().getResult().getOutput().getText();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** 查找结果载体：嵌入向量（写入复用）+ 命中载荷（null = 未命中） */
    private record LookupOutcome(float[] vector, SemanticCacheService.CacheHit hit) {
    }

    @Override
    public String getName() {
        return "CacheCheckAdvisor";
    }

    /** 11.2 链序表：路由(440)/溯源(450)之后、门控(500)之前 */
    @Override
    public int getOrder() {
        return 460;
    }
}
