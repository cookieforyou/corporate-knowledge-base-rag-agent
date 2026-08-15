package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 根因标注请求（Phase 4 簇④ 4.7）：rootCause ∈ RootCause 四分类。
 */
public record RootCauseRequest(
    @NotBlank(message = "rootCause 不能为空") String rootCause) {
}
