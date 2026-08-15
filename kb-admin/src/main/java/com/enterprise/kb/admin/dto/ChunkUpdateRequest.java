package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Chunk 编辑请求体（Phase 4 簇③ 4.4）。
 *
 * <p>上限 30000 字符：切分器目标块 ~800 字符，编辑为局部修正场景，
 * 超大内容应走文档替换（replace）重入库而非 chunk 编辑。
 */
public record ChunkUpdateRequest(
    @NotBlank(message = "content 不能为空")
    @Size(max = 30000, message = "content 长度不能超过 30000 字符")
    String content) {
}
