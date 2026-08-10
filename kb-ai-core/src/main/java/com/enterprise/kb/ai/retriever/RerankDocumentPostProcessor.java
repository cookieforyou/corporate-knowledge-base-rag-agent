package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.ai.config.RetrievalProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排序后处理器（设计文档 10.5）—— DashScope rerank API（qwen3-rerank）
 *
 * <p>对 RRF 融合后的 recallSize（topK×2）候选精排并截断至 topK。
 * 实现 {@link DocumentPostProcessor}，2.10 组装 Advisor 链时挂载。
 *
 * <p>降级策略（不阻塞主链路）：endpoint 未配置或调用失败 →
 * 按 fusion_score 截断至 topK（退化为纯 RRF 排序）。
 *
 * <p>可插拔：未来切换 Qwen3-Reranker 私有部署 / Jina v3 等只需替换本实现 + 配置。
 */
@Slf4j
@Component
public class RerankDocumentPostProcessor implements DocumentPostProcessor {

    private final JsonMapper jsonMapper;
    private final RestClient restClient;
    private final boolean enabled;
    private final String model;
    private final String apiKey;
    /** 检索调优参数：topK 决定 rerank top_n 与截断条数（rag.retrieval.top-k） */
    private final RetrievalProperties properties;

    public RerankDocumentPostProcessor(
            JsonMapper jsonMapper,
            RetrievalProperties properties,
            @Value("${rag.rerank.endpoint:}") String endpoint,
            @Value("${rag.rerank.model:qwen3-rerank}") String model,
            @Value("${rag.rerank.api-key:}") String apiKey) {
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.enabled = endpoint != null && !endpoint.isBlank();
        this.model = model;
        this.apiKey = apiKey;
        this.restClient = enabled
            ? RestClient.builder().baseUrl(endpoint).build()
            : null;
        if (!enabled) {
            log.warn("rag.rerank.endpoint 未配置，重排序降级为 fusion_score 截断");
        }
    }

    @Override
    public @NonNull List<Document> process(@NonNull Query query, @NonNull List<Document> documents) {
        long start = System.currentTimeMillis();
        List<Document> top = doProcess(query, documents);
        // 最终注入序列 trace（source=final）：[ref-N] 标注与本列表下标一一对应（11.1.2）
        recordFinalTrace(query, top, System.currentTimeMillis() - start);
        return top;
    }

    private @NonNull List<Document> doProcess(@NonNull Query query, @NonNull List<Document> documents) {
        if (!enabled || documents.isEmpty()) {
            return truncateByFusionScore(documents);
        }
        try {
            // qwen3-rerank compatible-api/v1/reranks 为扁平契约（2026-08-04 E2E 修正）：
            // query/documents/top_n 与 model 同层——嵌套 input/parameters 是 gte-rerank 系
            // DashScope 原生端点的旧契约，误用会被拒（400 Field required: input.query）。
            // top_n 超过候选数同样报 InvalidParameter，按候选数收敛。
            // 契约外参数不传：return_documents（gte 系参数，官方容忍但非契约字段）；
            // instruct（可选任务指令，默认即问答检索任务，与 RAG 场景契合，显式传值无增益）。
            Map<String, Object> body = Map.of(
                "model", model,
                "query", query.text(),
                "documents", documents.stream().map(Document::getText).toList(),
                "top_n", Math.min(properties.getTopK(), documents.size()));

            String raw = restClient.post()
                .headers(h -> h.setBearerAuth(apiKey))
                .body(body)
                .retrieve()
                .body(String.class);

            List<RerankResult> results;
            try {
                RerankResponse response = jsonMapper.readValue(raw, RerankResponse.class);
                results = response.effectiveResults();
            } catch (Exception parseError) {
                log.warn("rerank 响应解析失败，降级为 fusion_score 截断: {}, 原文: {}",
                    parseError.getMessage(), raw == null ? "null"
                        : raw.substring(0, Math.min(raw.length(), 500)));
                return truncateByFusionScore(documents);
            }
            if (results == null) {
                log.warn("rerank 响应结构异常（无 results），降级为 fusion_score 截断，原文: {}",
                    raw.substring(0, Math.min(raw.length(), 500)));
                return truncateByFusionScore(documents);
            }

            List<Document> reranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                RerankResult r = results.get(i);
                if (r.index() < 0 || r.index() >= documents.size()) continue;
                Document src = documents.get(r.index());
                Map<String, Object> meta = new HashMap<>(src.getMetadata());
                meta.put("rerank_score", r.relevanceScore());
                meta.put("rerank_rank", i + 1);
                reranked.add(Document.builder()
                    .id(src.getId())
                    .text(src.getText())
                    .metadata(meta)
                    .score(r.relevanceScore())
                    .build());
            }
            // API 结果已按相关性降序；防御性再排序 + 截断
            return reranked.stream()
                .sorted(Comparator.comparingDouble(
                    (Document d) -> (Double) d.getMetadata().get("rerank_score")).reversed())
                .limit(properties.getTopK())
                .toList();
        } catch (Exception e) {
            log.warn("rerank 调用失败，降级为 fusion_score 截断: {}", e.getMessage());
            return truncateByFusionScore(documents);
        }
    }

    /** 降级路径：按融合分截断至 topK（fusion_score 缺失时回退 Document.score） */
    private List<Document> truncateByFusionScore(List<Document> documents) {
        return documents.stream()
            .sorted(Comparator.comparingDouble(RerankDocumentPostProcessor::sortScore).reversed())
            .limit(properties.getTopK())
            .toList();
    }

    /** 最终序列写入检索上下文 trace（经 Query.context 参数化；无上下文降级跳过） */
    private void recordFinalTrace(Query query, List<Document> top, long latencyMs) {
        if (top.isEmpty()) {
            return;
        }
        RetrievalContext ctx = RetrievalContext.from(query);
        if (ctx != null) {
            ctx.addTraceEntry("final", top, latencyMs);
        }
    }

    private static double sortScore(Document d) {
        Object fusion = d.getMetadata().get("fusion_score");
        if (fusion instanceof Number n) return n.doubleValue();
        return d.getScore() != null ? d.getScore() : 0.0;
    }

    // ── rerank API 响应模型（2026-08-04 实证修正）──
    // qwen3-rerank compatible 端点：results 位于响应顶层；旧 gte-rerank 原生端点在
    // output.results——双形态兼容解析，切换后端不改代码。

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<RerankResult> results, Output output) {
        List<RerankResult> effectiveResults() {
            if (results != null) {
                return results;
            }
            return output == null ? null : output.results();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(List<RerankResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {}
}
