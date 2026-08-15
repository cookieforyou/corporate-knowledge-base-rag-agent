package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Golden Set 回灌请求（Phase 4 簇④ 4.7）——Bad Case 审计行转 Golden 用例。
 *
 * @param auditLogId       源审计行（question 取其 query_text，租户守卫凭此）
 * @param category         Golden 用例分类（FACTOID/REASONING/TABLE/MULTI_DOC/NEGATIVE；
 *                         注入样本走独立标注流程不收编）
 * @param expectedChunkIds 期望命中 chunk ID（确定性 ID；可空 = 仅评生成侧）
 * @param expectedDocs     期望命中文件名（可空 = 跳过文档级兜底指标）
 * @param expectedAnswer   理想回答（可空；通常取自反馈期望回答人工修订）
 * @param expectedKeywords 期望关键词（可空）
 */
public record ReingestRequest(
    @NotNull(message = "auditLogId 不能为空") Long auditLogId,
    String category,
    List<String> expectedChunkIds,
    List<String> expectedDocs,
    String expectedAnswer,
    String expectedKeywords) {
}
