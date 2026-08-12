package com.enterprise.kb.eval.dataset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Golden Dataset 标准问答对（设计文档 16.1）
 *
 * <p>字段可选性与指标联动：
 * <ul>
 *   <li>{@code expectedChunkIds} 为空 → 该条跳过 Top-K Recall / MRR / Context Precision（仅评生成侧）</li>
 *   <li>{@code expectedDocs} 为空 → 该条跳过文档级兜底检索指标（簇④ A4 修复，16 章 v2.21）</li>
 *   <li>{@code expectedAnswer} 为空 → 跳过 Answer Correctness（Phase 5 指标）</li>
 *   <li>{@code category = NEGATIVE} → 走 Negative Rejection 判定，不评 Faithfulness</li>
 * </ul>
 *
 * @param id               用例唯一标识（如 factoid-001）
 * @param category         分类
 * @param question         测试问题
 * @param expectedKeywords 期望包含的关键词（宽松匹配，可空）
 * @param expectedAnswer   理想回答（LLM-as-Judge 严格评分用，可空）
 * @param expectedChunkIds 期望命中的 Chunk ID 列表（chunk 级检索指标用，可空；
 *                         chunk ID 为确定性 ID——重入库不变，见 9.3 v2.22）
 * @param expectedDocs     期望命中的文件名列表（文档级兜底检索指标用，可空）——
 *                         匹配键为检索命中元数据 file_name；跨重入库/解析漂移/
 *                         contextual 增强恒稳定，是 chunk 级失配时的度量兜底
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoldenQAPair(
    String id,
    QACategory category,
    String question,
    String expectedKeywords,
    String expectedAnswer,
    List<String> expectedChunkIds,
    List<String> expectedDocs
) {
    public boolean isNegative() {
        return category == QACategory.NEGATIVE;
    }

    public boolean hasRetrievalExpectation() {
        return expectedChunkIds != null && !expectedChunkIds.isEmpty();
    }

    public boolean hasDocExpectation() {
        return expectedDocs != null && !expectedDocs.isEmpty();
    }
}
