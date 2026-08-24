package com.enterprise.kb.ai.cache;

import java.time.Instant;
import java.util.List;

/**
 * 语义缓存条目（Phase 5 簇③ 5.6）。
 *
 * <p>问答对 + 可重放溯源载荷：命中时缓存回答经 SSE 重放，[ref-N] 锚定与 TRACE
 * 溯源事件须与原始回答一致——{@code traceJson} 承载流末 TRACE 事件的序列化载荷
 * （{@code List<SourceTrace>} 形态，kb-api SSE 协议 11.3），重放时原样反序列化下发，
 * 不重新检索（命中语义即「证据与生成已固化」）。
 *
 * <p>{@code docIds} 为答案引用的文档 ID 集合（冗余进 Redis TAG 字段）：知识库内容
 * 变更时按文档反查失效（失效接线见批2），无需逐条解析溯源载荷。
 *
 * <p>入库门槛（批2 advisor 写入侧守卫）：仅审计三态 SUCCESS + 证据非空回答可入缓存；
 * REJECTED/ERROR（护栏替换话术/异常响应）与空证据拒答不入——与反馈导出管道
 * （16.6.1）同款训练/复用材料质量纪律。
 *
 * @param question   用户问题（经入口护栏归一化 + PII 掩码后的形态——缓存键与重放问句同构）
 * @param answer     完整回答文本（含 [ref-N] 锚定标注）
 * @param traceJson  TRACE 溯源载荷 JSON（{@code List<SourceTrace>} 序列化）
 * @param docIds     答案引用文档 ID 集合（失效反查用，TAG 冗余）
 * @param createdAt  写入时刻
 */
public record SemanticCacheEntry(
        String question,
        String answer,
        String traceJson,
        List<String> docIds,
        Instant createdAt) {
}
