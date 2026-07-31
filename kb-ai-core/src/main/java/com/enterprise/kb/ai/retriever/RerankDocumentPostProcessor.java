package com.enterprise.kb.ai.retriever;

import com.enterprise.kb.commons.constant.Constants;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private final RestClient restClient;
    private final boolean enabled;
    private final String model;
    private final String apiKey;

    public RerankDocumentPostProcessor(
            @Value("${rag.rerank.endpoint:}") String endpoint,
            @Value("${rag.rerank.model:qwen3-rerank}") String model,
            @Value("${rag.rerank.api-key:${DASHSCOPE_API_KEY:}}") String apiKey) {
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
    public List<Document> process(Query query, List<Document> documents) {
        if (!enabled || documents.isEmpty()) {
            return truncateByFusionScore(documents);
        }
        try {
            Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of(
                    "query", query.text(),
                    "documents", documents.stream().map(Document::getText).toList()),
                "parameters", Map.of(
                    "top_n", Constants.DEFAULT_TOP_K,
                    "return_documents", false));

            RerankResponse response = restClient.post()
                .headers(h -> h.setBearerAuth(apiKey))
                .body(body)
                .retrieve()
                .body(RerankResponse.class);

            if (response == null || response.output() == null || response.output().results() == null) {
                log.warn("rerank 响应结构异常，降级为 fusion_score 截断");
                return truncateByFusionScore(documents);
            }

            List<Document> reranked = new ArrayList<>();
            List<RerankResult> results = response.output().results();
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
                .limit(Constants.DEFAULT_TOP_K)
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
            .limit(Constants.DEFAULT_TOP_K)
            .toList();
    }

    private static double sortScore(Document d) {
        Object fusion = d.getMetadata().get("fusion_score");
        if (fusion instanceof Number n) return n.doubleValue();
        return d.getScore() != null ? d.getScore() : 0.0;
    }

    // ── DashScope rerank API 响应模型（契约以控制台文档为准，2.2/2.9 核验）──

    record RerankResponse(Output output) {}

    record Output(List<RerankResult> results) {}

    record RerankResult(int index, @JsonProperty("relevance_score") double relevanceScore) {}
}
