package com.enterprise.kb.ai.advisor;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.commons.security.TextSanitizer;
import com.enterprise.kb.domain.model.KbAuditLog;
import com.enterprise.kb.domain.repository.KbAuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全链路审计日志 Advisor（设计文档 11.2 链序表 order 10 / 11.6，任务 3.12）
 *
 * <p><b>链序位语义</b>：最外层（10）——v2 重排原则「审计保持最外层以记录被拒/
 * 被限流的攻击请求」。同步路径经 adviseCall try/catch 包裹、流式路径经
 * doOnComplete/doOnError 旁路捕获——内层任意 Advisor 抛错（限流 429 / 注入拦截 /
 * 预算超额）均落审计，错误语义原样上抛不吞。
 *
 * <p><b>双链路形态（3.19 后）</b>：ragAgentChatClient 与 toolAgentChatClient 均挂
 * 本 Advisor；mode 经 ChatService advisor 参数（{@link #MODE_KEY}）区分。kb-eval
 * 评估链不挂（评估流量不污染审计）。
 *
 * <p><b>数据源</b>：query_text 取 Prompt 末条用户消息——order 10 先于
 * InputSanitize(300)，落库前经同款 sanitize 规则脱敏（3.5 PII 不绕过审计落库，
 * 与 Controller 归档同策）；rewritten_query 经 RewriteCapturingQueryTransformer
 * 写回的 RetrievalContext 捕获；检索/重排 chunk 与工具调用记录来自
 * RetrievalContext（双路 trace + toolCalls）；usage 取响应 metadata（v2.19 簇③ D1
 * 起主/备模型均开 include_usage，流式末块携带 usage；缺失时仍 null 降级）。
 *
 * <p><b>容错策略</b>：审计是旁路增值数据——构建/落库任何环节失败仅告警丢弃，
 * 绝不击穿问答（ChatSessionService 归档同款哲学）；落库走 auditExecutor
 * 虚拟线程异步，不占响应路径延迟。
 */
@Slf4j
@Component
public class AuditTraceAdvisor implements BaseAdvisor {

    /** advisor 参数键：问答模式（rag|tool），RagChatService/ToolChatService 注入 */
    public static final String MODE_KEY = "kb.audit_mode";

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_ERROR = "ERROR";

    private final KbAuditLogRepository auditLogRepository;
    private final JsonMapper jsonMapper;
    private final AsyncTaskExecutor auditExecutor;
    private final AiBusinessMetrics metrics;
    private final boolean enabled;

    public AuditTraceAdvisor(KbAuditLogRepository auditLogRepository,
                             JsonMapper jsonMapper,
                             @Qualifier("auditExecutor") AsyncTaskExecutor auditExecutor,
                             AiBusinessMetrics metrics,
                             @Value("${rag.audit.enabled:true}") boolean enabled) {
        this.auditLogRepository = auditLogRepository;
        this.jsonMapper = jsonMapper;
        this.auditExecutor = auditExecutor;
        this.metrics = metrics;
        this.enabled = enabled;
        log.info("全链路审计 Advisor 装配: enabled={}", enabled);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /** 同步路径：内层抛错（被拒/被限流）也落审计，错误原样上抛 */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (!enabled) {
            return chain.nextCall(request);
        }
        long startMs = System.currentTimeMillis();
        ChatClientRequest processed = before(request, chain);
        try {
            ChatClientResponse response = after(chain.nextCall(processed), chain);
            record(processed, response.chatResponse(), textOf(response.chatResponse()), null, startMs);
            return response;
        } catch (RuntimeException e) {
            record(processed, null, null, e, startMs);
            throw e;
        }
    }

    /**
     * 流式路径：token 原样穿透不缓冲（审计不得损 TTFT）——doOnNext 累积回答文本，
     * doOnComplete 落成功审计（聚合全文 + 末块 usage），doOnError 落拒绝/失败审计。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!enabled) {
            return chain.nextStream(request);
        }
        long startMs = System.currentTimeMillis();
        ChatClientRequest processed = before(request, chain);
        StringBuilder answerBuffer = new StringBuilder();
        AtomicReference<ChatResponse> lastChatResponse = new AtomicReference<>();
        return chain.nextStream(processed)
            .doOnNext(response -> {
                ChatResponse chatResponse = response.chatResponse();
                if (chatResponse != null) {
                    lastChatResponse.set(chatResponse);
                    String text = textOf(chatResponse);
                    if (text != null) {
                        answerBuffer.append(text);
                    }
                }
            })
            .doOnComplete(() -> record(processed, lastChatResponse.get(),
                answerBuffer.toString(), null, startMs))
            .doOnError(e -> record(processed, null, null, e, startMs));
    }

    @Override
    public int getOrder() {
        return 10;
    }

    // ── 审计记录组装与落库 ──

    private void record(ChatClientRequest request, ChatResponse chatResponse,
                        String answer, Throwable error, long startMs) {
        try {
            Map<String, Object> context = request.context();
            RetrievalContext ctx = context.get(RetrievalContext.CONTEXT_KEY) instanceof RetrievalContext rc
                ? rc : null;
            String queryText = TextSanitizer.maskPii(userTextOf(request));

            // 快照先提取、再异步落库——RetrievalContext 为请求级共享实例
            AuditSnapshot snapshot = new AuditSnapshot(
                asString(context.get(MODE_KEY)),
                asString(context.get(ChatMemory.CONVERSATION_ID)),
                ctx == null ? null : ctx.getTenantId(),
                ctx == null ? null : ctx.getUserId(),
                ctx == null ? null : ctx.getTraceId(),
                ctx == null ? null : ctx.getRewrittenQuery(),
                ctx == null ? List.of() : ctx.getTraceSummary(),
                ctx == null ? List.of() : ctx.getToolCalls(),
                latency(startMs));

            recordBusinessMetrics(snapshot);
            auditExecutor.execute(() -> persistSafely(snapshot, queryText, answer, chatResponse, error));
        } catch (Exception e) {
            log.warn("审计记录构建失败，丢弃（旁路数据，不影响问答）: {}", e.getMessage());
        }
    }

    private void persistSafely(AuditSnapshot s, String queryText, String answer,
                               ChatResponse chatResponse, Throwable error) {
        try {
            KbAuditLog audit = new KbAuditLog();
            // trace_id 前移至 Controller 请求线程生成（3.17：经 DONE 帧/同步响应送达前端，
            // 反馈 API 凭此确定性关联审计行）；kb-eval 等无 ctx 入口回落自生成
            audit.setTraceId(s.traceId() != null ? s.traceId() : UUID.randomUUID().toString());
            audit.setMode(s.mode());
            audit.setSessionId(s.sessionId());
            audit.setTenantId(s.tenantId());
            audit.setUserId(s.userId());
            audit.setQueryText(queryText == null ? "" : queryText);
            audit.setRewrittenQuery(s.rewrittenQuery());
            audit.setRetrievalType(s.traceEntries().isEmpty() ? null : "hybrid");
            audit.setRetrievedChunks(toJsonOrNull(retrievalProjection(s.traceEntries(), false)));
            audit.setRerankedChunks(toJsonOrNull(retrievalProjection(s.traceEntries(), true)));
            audit.setFinalAnswer(answer);
            audit.setToolCalls(s.toolCalls().isEmpty() ? null : toJsonOrNull(s.toolCalls()));
            audit.setLatencyMs(s.latencyMs());
            if (chatResponse != null) {
                audit.setModelName(chatResponse.getMetadata().getModel());
                audit.setTokenUsage(usageJsonOf(chatResponse.getMetadata().getUsage()));
            }
            if (error == null) {
                audit.setStatus(STATUS_SUCCESS);
            } else if (error instanceof BusinessException be) {
                audit.setStatus(STATUS_REJECTED);
                audit.setErrorCode(be.getErrorCode());
            } else {
                audit.setStatus(STATUS_ERROR);
                audit.setErrorCode(error.getClass().getSimpleName());
            }
            auditLogRepository.save(audit);
        } catch (Exception e) {
            log.warn("审计落库失败，丢弃（旁路数据，不影响问答）: {}", e.getMessage());
        }
    }

    /**
     * 业务指标旁路计数（3.13）：审计 Advisor 天然遍历双链全量请求且可读 trace/
     * toolCalls 快照，命中率与工具成功率在此统计——
     * <ul>
     *   <li>检索命中率：rag 模式且产生过 trace 条目（到达过检索层）计 total，
     *       final 重排序列非空计 hit；内层护栏/限流提前拒绝未达检索，不计入分母</li>
     *   <li>工具调用：按 ToolCall.status 分桶（成功/挂起见 AiBusinessMetrics）</li>
     * </ul>
     */
    private void recordBusinessMetrics(AuditSnapshot snapshot) {
        try {
            if ("rag".equals(snapshot.mode()) && !snapshot.traceEntries().isEmpty()) {
                boolean hit = snapshot.traceEntries().stream()
                    .anyMatch(e -> "final".equals(e.source()) && !e.documents().isEmpty());
                metrics.recordRetrieval(hit);
            }
            for (RetrievalContext.ToolCall toolCall : snapshot.toolCalls()) {
                metrics.recordToolCall(toolCall.status());
            }
        } catch (Exception e) {
            log.warn("业务指标计数失败，跳过（旁路数据，不影响问答与审计）: {}", e.getMessage());
        }
    }

    /** 请求快照（异步落库前提取，避免跨线程读共享实例竞态） */
    private record AuditSnapshot(String mode, String sessionId, String tenantId, String userId,
                                 String traceId, String rewrittenQuery,
                                 List<RetrievalContext.TraceEntry> traceEntries,
                                 List<RetrievalContext.ToolCall> toolCalls, int latencyMs) {}

    private static int latency(long startMs) {
        return (int) Math.min(System.currentTimeMillis() - startMs, Integer.MAX_VALUE);
    }

    /** 检索 trace 轻量投影：final 源为重排序列，其余为双路原始命中 */
    private List<Map<String, Object>> retrievalProjection(List<RetrievalContext.TraceEntry> entries, boolean reranked) {
        List<Map<String, Object>> projection = new ArrayList<>();
        for (RetrievalContext.TraceEntry entry : entries) {
            boolean isFinal = "final".equals(entry.source());
            if (isFinal != reranked) {
                continue;
            }
            for (Document doc : entry.documents()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunk_id", asString(doc.getMetadata().get("chunk_id")));
                item.put("file_name", asString(doc.getMetadata().get("file_name")));
                Object pageNum = doc.getMetadata().get("page_num");
                if (pageNum != null) {
                    item.put("page_num", pageNum);
                }
                Object score = isFinal
                    ? doc.getMetadata().get("rerank_score")
                    : doc.getMetadata().get("fusion_score");
                if (score != null) {
                    item.put("score", score);
                }
                projection.add(item);
            }
        }
        return projection;
    }

    /** usage JSON（2.0 GA getTotalTokens 返回 Integer 可空，判空——坑位⑧） */
    private String usageJsonOf(Usage usage) {
        if (usage == null || usage.getTotalTokens() == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("prompt_tokens", usage.getPromptTokens());
        map.put("completion_tokens", usage.getCompletionTokens());
        map.put("total_tokens", usage.getTotalTokens());
        return toJsonOrNull(map);
    }

    private String toJsonOrNull(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("审计 JSON 序列化失败，字段置空: {}", e.getMessage());
            return null;
        }
    }

    private static String textOf(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
            || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private static String userTextOf(ChatClientRequest request) {
        return request.prompt().getInstructions().stream()
            .filter(m -> m.getMessageType() == MessageType.USER)
            .map(Message::getText)
            .reduce((first, second) -> second)
            .orElse(null);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
