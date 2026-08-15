package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 反馈处理态更新请求（Phase 4 簇④ 4.7）：Bad Case 处置完毕人工闭环标记。
 */
public record ResolvedRequest(
    @NotNull(message = "resolved 不能为空") Boolean resolved) {
}
