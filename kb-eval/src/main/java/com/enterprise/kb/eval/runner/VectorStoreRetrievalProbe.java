package com.enterprise.kb.eval.runner;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单路向量检索探针 —— Phase 1 检索形态的度量基线
 *
 * <p>直接调用 Spring AI {@link VectorStore}（即 Phase 1 QuestionAnswerAdvisor 的底层检索），
 * 建立单路检索的质量基线。**始终注册**，与混合探针共存：auto 模式经 min-order
 * 选择混合探针（order 0 &lt; 100），{@code eval.probe=vector} 显式指定本探针——
 * 二者构成 A/B 基线对比（曾用 @ConditionalOnMissingBean 自动退让，与 A/B 开关
 * 互斥：混合探针恒在导致本探针永不注册，eval.probe=vector 启动即失败，2026-08-01 修复）。
 *
 * <p>chunkId 约定：ETL 写入时 Document.id = kb_chunk.id（第九章 9.3 不变量），
 * 兼容读取 metadata.chunk_id。
 */
@Component
public class VectorStoreRetrievalProbe implements RetrievalProbe {

    private final VectorStore vectorStore;

    public VectorStoreRetrievalProbe(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<ProbeHit> probe(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.0)   // 评估期不过滤，完整观测排序分布
            .build();
        return vectorStore.similaritySearch(request).stream()
            .map(this::toHit)
            .toList();
    }

    private ProbeHit toHit(Document doc) {
        Object chunkId = doc.getMetadata().get("chunk_id");
        String id = chunkId != null ? chunkId.toString() : doc.getId();
        double score = doc.getScore() != null ? doc.getScore() : 0.0;
        return new ProbeHit(id, doc.getText(), score);
    }

    @Override
    public String name() {
        return "vector-single";
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
