package com.enterprise.kb.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 知识库统计总览（Phase 4 簇② 任务 4.6：运维仪表盘数据接口）
 *
 * <p>全部按 JWT 租户隔离；聚合走 kb_document 单表（chunk 规模取
 * chunk_count 文档侧口径，软删精确口径属簇③ 运维域）。
 *
 * @param documentTotal       租户文档总数
 * @param documentsByStatus   解析状态分布（DocumentStatus 五值全量，缺省补 0）
 * @param chunkTotal          存活 chunk 总数（SUCCESS 文档 chunk_count 求和）
 * @param documentsByParseRoute 解析路由分布（NATIVE/DEEP/OCR；未解析文档归 UNKNOWN）
 * @param dailyIngestion      近 14 天入库趋势（无数据日补 0，供前端连续绘图）
 * @param badCaseTotal        点踩反馈总数（12 §12.12 二轮：仪表盘计数聚合进 stats 域，
 *                            与 admin 域审计查询解耦——聚合数字全员可读）
 * @param unannotatedTotal    待标注根因数（点踩 ∧ root_cause 空）
 */
public record StatsOverview(
    long documentTotal,
    Map<String, Long> documentsByStatus,
    long chunkTotal,
    Map<String, Long> documentsByParseRoute,
    List<DailyIngestion> dailyIngestion,
    long badCaseTotal,
    long unannotatedTotal) {

    /**
     * 单日入库量
     *
     * @param date      日期（yyyy-MM-dd）
     * @param documents 当日入库文档数
     * @param chunks    当日入库文档当前 chunk 总量
     */
    public record DailyIngestion(String date, long documents, long chunks) {}
}
