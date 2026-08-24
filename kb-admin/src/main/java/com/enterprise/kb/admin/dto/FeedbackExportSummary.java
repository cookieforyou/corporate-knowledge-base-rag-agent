package com.enterprise.kb.admin.dto;

/**
 * 反馈微调数据导出概览（簇② 5.10 批4）——dry-run 计数 + 门槛对照，零内容输出。
 *
 * <p>门槛值（{@code sftTarget}/{@code dpoTarget}）为百炼微调通道的数据量
 * 建议线（SFT≥100 / DPO≥50 对），导出只报告不阻断——是否送训由运营决策。
 *
 * @param totalFeedback              租户域反馈总数
 * @param positiveFeedback           👍 反馈数
 * @param negativeFeedback           👎 反馈数
 * @param negativeWithExpectedAnswer 👎 且附用户期望回答数（DPO 对与订正式 SFT 的原料）
 * @param sftRecords                 SFT 可导出条数（正向采纳 + 用户订正两通道合计）
 * @param sftFromPositive            其中：👍 采纳通道（问题 + 系统原回答）
 * @param sftFromCorrection          其中：👎 订正通道（问题 + 用户期望回答）
 * @param dpoRecords                 DPO 偏好对可导出数（👎 + 期望回答 + 原回答齐备）
 * @param skippedMissingConversation 会话材料不全跳过数（问题缺失 / 👍 但回答缺失）
 * @param skippedAuditNotClean       审计结局非 SUCCESS 跳过数（REJECTED/ERROR 不入训练材料）
 * @param negativeWithoutCorrection  👎 未附期望回答数（无导出原料，仅 Bad Case 运营面消费）
 * @param piiMaskedRecords           导出前命中 PII 掩码的记录数（共享注册表同款消毒）
 */
public record FeedbackExportSummary(
    int totalFeedback,
    int positiveFeedback,
    int negativeFeedback,
    int negativeWithExpectedAnswer,
    int sftRecords,
    int sftFromPositive,
    int sftFromCorrection,
    int dpoRecords,
    int skippedMissingConversation,
    int skippedAuditNotClean,
    int negativeWithoutCorrection,
    int piiMaskedRecords,
    int sftTarget,
    boolean sftTargetMet,
    int dpoTarget,
    boolean dpoTargetMet) {
}
