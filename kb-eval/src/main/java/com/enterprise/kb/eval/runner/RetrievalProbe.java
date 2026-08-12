package com.enterprise.kb.eval.runner;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * 检索探针抽象 —— 评估器与被测检索链路的解耦点
 *
 * <p>演进路径（与 Phase 2 主线联动）：
 * <ul>
 *   <li>Phase 2.16（当前）：{@link VectorStoreRetrievalProbe}（order=100），
 *       度量 Phase 1 单路向量检索基线</li>
 *   <li>Phase 2.7+ ：提供 HybridRetrievalProbe（order=0，自动胜出），
 *       直接注入 HybridDocumentRetriever 度量混合检索——评估器代码零改动</li>
 * </ul>
 */
public interface RetrievalProbe extends Ordered {

    /**
     * @param fileName 来源文件名（向量/ES 元数据 file_name，缺失时 null）——
     *                 文档级兜底指标的匹配键（簇④ A4 修复，16 章 v2.21）
     */
    record ProbeHit(String chunkId, String fileName, String content, double score) {}

    /** 按 query 返回 Top-K 命中（chunkId 与 kb_chunk.id 一致） */
    List<ProbeHit> probe(String query, int topK);

    /** 探针名称，写入评估报告（如 vector-single / hybrid） */
    String name();
}
