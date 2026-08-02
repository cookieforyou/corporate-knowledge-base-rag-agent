package com.enterprise.kb.api.controller;

import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.api.dto.RetrievalDebugResult;
import com.enterprise.kb.api.dto.RetrievalDebugResult.Candidate;
import com.enterprise.kb.api.dto.RetrievalDebugResult.Latency;
import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.commons.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试 API（设计文档 10.7，任务 2.14）
 *
 * <p>直调检索链路（<b>不经 LLM</b>）：改写 → 双路召回 → RRF 融合 → 重排，
 * 输出每个候选 Chunk 的全维度得分与各阶段耗时，供前端检索调试台与 Bad Case 排查。
 * 检索上下文（租户过滤 + trace 收集）复用主链路的参数化机制（RetrievalContext）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/retrieval")
@RequiredArgsConstructor
public class RetrievalDebugController {

    private final HybridDocumentRetriever hybridRetriever;
    private final RerankDocumentPostProcessor rerankPostProcessor;
    private final RewriteQueryTransformer rewriteQueryTransformer;
    private final JwtUtils jwtUtils;

    @PostMapping("/search")
    public ApiResponse<RetrievalDebugResult> search(@RequestBody Map<String, String> body) {
        String queryText = body.get("query");
        log.info("检索调试: user={}, query={}", jwtUtils.getCurrentUsername(), queryText);

        // 每请求检索上下文：租户过滤生效 + trace 全量收集（与主链路同一参数化机制）
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(jwtUtils.getCurrentTenantId());
        ctx.setUserId(jwtUtils.getCurrentUserId());
        Map<String, Object> queryContext = Map.of(RetrievalContext.CONTEXT_KEY, ctx);

        long t0 = System.currentTimeMillis();
        // 调试端点恒定展示改写结果（不受 rag.retrieval.rewrite.enabled 影响——排查需要看到改写形态）
        Query rewritten = rewriteQueryTransformer.apply(new Query(queryText));
        long t1 = System.currentTimeMillis();

        List<Document> fused = hybridRetriever.retrieve(
            Query.builder().text(rewritten.text()).context(queryContext).build());
        long t2 = System.currentTimeMillis();

        List<Document> finals = rerankPostProcessor.process(
            Query.builder().text(rewritten.text()).context(queryContext).build(), fused);
        long t3 = System.currentTimeMillis();

        return ApiResponse.success(assemble(queryText, rewritten.text(),
            new Latency(t1 - t0, t2 - t1, t3 - t2, t3 - t0), ctx, finals));
    }

    /**
     * 合并双路原始命中（含未进最终 Top-N 的候选）+ 最终序列得分为统一候选视图。
     * 降级判据（10.2 降级矩阵的可观测投影）：trace 条目缺失即该路失败。
     */
    private RetrievalDebugResult assemble(String query, String rewrittenQuery, Latency latency,
                                          RetrievalContext ctx, List<Document> finals) {
        Map<String, CandidateBuilder> merged = new LinkedHashMap<>();
        boolean bm25Present = false;
        boolean vectorHasHits = false;

        for (RetrievalContext.TraceEntry entry : ctx.getTraceSummary()) {
            switch (entry.source()) {
                case "bm25" -> {
                    bm25Present = true;
                    for (Document doc : entry.documents()) {
                        builder(merged, doc).fillBm25(doc);
                    }
                }
                case "vector" -> {
                    vectorHasHits = !entry.documents().isEmpty();
                    int rank = 0;
                    for (Document doc : entry.documents()) {
                        rank++;
                        builder(merged, doc).fillVector(doc, rank);
                    }
                }
                default -> { /* final 序列单独处理 */ }
            }
        }

        int finalRank = 0;
        for (Document doc : finals) {
            finalRank++;
            builder(merged, doc).fillFinal(doc, finalRank);
        }

        List<Candidate> candidates = new ArrayList<>(merged.size());
        for (CandidateBuilder b : merged.values()) {
            candidates.add(b.build());
        }
        // 最终排名优先，其次按融合分降序——调试台首屏即最终注入顺序
        candidates.sort((a, b) -> {
            if (a.finalRank() != null && b.finalRank() != null) {
                return Integer.compare(a.finalRank(), b.finalRank());
            }
            if (a.finalRank() != null) return -1;
            if (b.finalRank() != null) return 1;
            return Double.compare(
                b.fusionScore() != null ? b.fusionScore() : 0,
                a.fusionScore() != null ? a.fusionScore() : 0);
        });

        return new RetrievalDebugResult(query, rewrittenQuery, latency, candidates, Map.of(
            "vector", vectorHasHits ? "OK" : "DEGRADED",
            "bm25", bm25Present ? "OK" : "DEGRADED"));
    }

    private static CandidateBuilder builder(Map<String, CandidateBuilder> merged, Document doc) {
        String chunkId = doc.getId();
        return merged.computeIfAbsent(chunkId, id -> new CandidateBuilder(doc));
    }

    /** 候选累加器：各路得分按 chunkId 汇聚（chunk 融合键不变量，9.3） */
    private static final class CandidateBuilder {
        private final String chunkId;
        private String fileName;
        private Integer pageNum;
        private String chunkType;
        private String content;
        private Double vectorScore;
        private Integer vectorRank;
        private Double bm25Score;
        private Integer bm25Rank;
        private Double fusionScore;
        private Double rerankScore;
        private Integer rerankRank;
        private Integer finalRank;

        CandidateBuilder(Document doc) {
            this.chunkId = doc.getId();
            fillCommon(doc);
        }

        private void fillCommon(Document doc) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta.get("file_name") != null) this.fileName = String.valueOf(meta.get("file_name"));
            if (meta.get("page_num") instanceof Number n) this.pageNum = n.intValue();
            if (meta.get("chunk_type") != null) this.chunkType = String.valueOf(meta.get("chunk_type"));
            if (doc.getText() != null && !doc.getText().isBlank()) this.content = doc.getText();
        }

        void fillVector(Document doc, int rank) {
            this.vectorRank = rank;
            this.vectorScore = doc.getScore();
            fillCommon(doc);
        }

        void fillBm25(Document doc) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta.get("bm25_score") instanceof Number n) this.bm25Score = n.doubleValue();
            if (meta.get("bm25_rank") instanceof Number n) this.bm25Rank = n.intValue();
            fillCommon(doc);
        }

        void fillFinal(Document doc, int rank) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta.get("fusion_score") instanceof Number n) this.fusionScore = n.doubleValue();
            if (meta.get("rerank_score") instanceof Number n) this.rerankScore = n.doubleValue();
            if (meta.get("rerank_rank") instanceof Number n) this.rerankRank = n.intValue();
            this.finalRank = rank;
            fillCommon(doc);
        }

        Candidate build() {
            return new Candidate(chunkId, fileName, pageNum, chunkType, content,
                vectorScore, vectorRank, bm25Score, bm25Rank,
                fusionScore, rerankScore, rerankRank, finalRank);
        }
    }
}
