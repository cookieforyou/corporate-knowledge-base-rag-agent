package com.enterprise.kb.admin.dto;

import java.time.LocalDateTime;

/**
 * 图谱回填任务视图（簇④ 5.1 批3）：单租户单任务形态（图谱回填是幂等收敛
 * 操作，无需任务历史列表——与重建任务表的多任务形态区分）。
 *
 * @param status     RUNNING / COMPLETED（无进行中任务 = null 态，视图缺省）
 * @param total      目标文档总数
 * @param succeeded  抽取成功写图数（{@code GraphStatus.COMPLETED}）
 * @param failed     抽取失败数（{@code GraphStatus.FAILED}，可重发收敛）
 * @param startedAt  任务受理时刻
 * @param finishedAt 任务终态时刻（RUNNING 态为 null）
 */
public record GraphBackfillView(
    String status,
    int total,
    long succeeded,
    long failed,
    LocalDateTime startedAt,
    LocalDateTime finishedAt) {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
}
