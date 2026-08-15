package com.enterprise.kb.admin.dto;

/**
 * Golden Set 回灌结果（Phase 4 簇④ 4.7）。
 *
 * @param goldenId          写入的 Golden 用例 ID（bc-{auditLogId} 确定性，重复回灌 upsert 覆写）
 * @param file              落盘文件路径（GoldenDatasetLoader classpath 扫描域内）
 * @param question          用例问题（= 审计行 query_text）
 * @param category          用例分类
 * @param resolvedFeedbackId 联动置 resolved 的反馈 ID（无关联反馈为 null）
 */
public record ReingestResult(
    String goldenId,
    String file,
    String question,
    String category,
    String resolvedFeedbackId) {
}
