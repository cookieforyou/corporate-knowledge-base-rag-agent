package com.enterprise.kb.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档解析状态视图（Phase 4 簇② 任务 4.6）：处理中文档清单 + 各处理态计数，
 * 供运维仪表盘「解析状态」面板消费。
 *
 * @param counts    处理中三态计数（UPLOADING/PARSING/REINDEXING 全量，缺省补 0）
 * @param documents 处理中文档列表（按更新时间倒序）
 */
public record DocumentProcessingView(
    Map<String, Long> counts,
    List<ProcessingDocument> documents) {

    /**
     * 处理中文档摘要（不含正文/错误详情，控制载荷体积）
     */
    public record ProcessingDocument(
        String id,
        String name,
        String status,
        String parseRoute,
        LocalDateTime updatedAt) {}
}
