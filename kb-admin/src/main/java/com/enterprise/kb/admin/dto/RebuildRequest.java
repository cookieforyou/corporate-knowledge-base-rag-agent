package com.enterprise.kb.admin.dto;

import java.util.List;

/**
 * 索引重建请求体（Phase 4 簇③ 4.5）。
 *
 * <p>{@code docIds} 为空/缺省 = 租户全量重建（SUCCESS + FAILED 文档）；
 * 指定 = 目标文档增量重建（漂移修复场景，逐文档所有权/状态校验，
 * 越权或处理中文档记 skipped 不阻断任务）。
 */
public record RebuildRequest(List<String> docIds) {
}
