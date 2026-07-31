package com.enterprise.kb.eval.runner;

import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 混合检索探针 —— 簇 B（2.6-2.9）落地后接入，度量混合检索全链路
 *
 * <p>order=0，自动模式下（eval.probe=auto）经 min-order 选择顶替
 * {@link VectorStoreRetrievalProbe}（order=100）——评估器与 Golden Dataset
 * 零改动，报告中的检索指标即切换为混合检索的度量，前后对比即调优收益。
 *
 * <p>注意：探针度量**检索**（向量+BM25+RRF），不含重排序——重排序是
 * Advisor 链的 DocumentPostProcessor（10.5），属生成前链路，非检索本征质量。
 */
@Component("hybridRetrievalProbe")
public class HybridRetrievalProbe implements RetrievalProbe {

    private final HybridDocumentRetriever hybridRetriever;

    public HybridRetrievalProbe(HybridDocumentRetriever hybridRetriever) {
        this.hybridRetriever = hybridRetriever;
    }

    @Override
    public List<ProbeHit> probe(String query, int topK) {
        return hybridRetriever.retrieve(new Query(query)).stream()
            .limit(topK)
            .map(HybridRetrievalProbe::toHit)
            .toList();
    }

    private static ProbeHit toHit(Document d) {
        Object chunkId = d.getMetadata().getOrDefault("chunk_id", d.getId());
        double score = d.getScore() != null ? d.getScore() : 0.0;
        return new ProbeHit(String.valueOf(chunkId), d.getText(), score);
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
