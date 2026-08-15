package com.enterprise.kb.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 重建任务视图（4.5）——Redis 任务表投影（v2.36，重启保留 TTL 窗口内、租户域隔离） */
public record RebuildTaskView(
    String taskId,
    String status,
    int total,
    int succeeded,
    int failed,
    int skipped,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    List<FailureView> failures) {

    /** 单文档失败/跳过明细（docId + 原因） */
    public record FailureView(String docId, String reason) {
    }
}
