package com.enterprise.kb.admin.dto;

import java.util.List;

/**
 * 图谱回填请求体（簇④ 5.1 批3）。
 *
 * @param docIds 目标文档（空/缺省 = 租户全量待回填文档——
 *               {@code graph_status} 为 PENDING/FAILED 且入库成功者，
 *               幂等重跑天然跳过已完成文档）
 */
public record GraphBackfillRequest(List<String> docIds) {
}
