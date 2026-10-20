package com.enterprise.kb.eval.probe;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * 检索探针抽象 —— 评估器与被测检索链路的解耦点
 *
 * <p>实现形态（auto 模式按 order 最小者胜出）：
 * <ul>
 *   <li>{@link HybridRetrievalProbe}（order=0）：默认探针，直接注入
 *       HybridDocumentRetriever 度量向量+BM25[+Graph] 多路检索</li>
 *   <li>{@link VectorStoreRetrievalProbe}（order=100）：单路向量基线，
 *       显式 {@code eval.probe=vector} 选用</li>
 *   <li>{@link ChainRetrievalProbe}（order=50）：完整 advisor 链，
 *       显式 {@code eval.probe=chain} 选用</li>
 * </ul>
 */
public interface RetrievalProbe extends Ordered {

    /**
     * @param fileName 来源文件名（向量/ES 元数据 file_name，缺失时 null）——
     *                 文档级兜底指标的匹配键（冲刺簇④ A4 修复，16 章 v2.21）
     */
    record ProbeHit(String chunkId, String fileName, String content, double score) {}

    /** 按 query 返回 Top-K 命中（chunkId 与 kb_chunk.id 一致） */
    List<ProbeHit> probe(String query, int topK);

    /** 探针名称，写入评估报告（如 vector-single / hybrid） */
    String name();
}
