package com.enterprise.kb.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 命中演练请求（安全簇⑥ F2）：输入文本 → 返回将命中词项清单。
 * 演练为纯运营视图——不计指标不落审计（专项方案 §4.6 F2 纪律）。
 */
public record DrillRequest(@NotBlank String text) {
}
