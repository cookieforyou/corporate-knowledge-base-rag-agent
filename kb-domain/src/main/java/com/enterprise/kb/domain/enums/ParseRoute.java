package com.enterprise.kb.domain.enums;

/**
 * 文档解析路由（设计文档 9.1 三路由）
 *
 * <ul>
 *   <li>NATIVE：Tika 原生解析（电子版本文档，便宜快速）</li>
 *   <li>DEEP：深度解析服务（DocMind 文档解析大模型版 / qwen3.5-ocr 备选，
 *       表格密集/复杂版式，返回 Markdown + HTML 表格块）</li>
 *   <li>OCR：扫描件兜底（低文本密度探测触发，qwen3.5-ocr 视觉识别）</li>
 * </ul>
 */
public enum ParseRoute {
    NATIVE,
    DEEP,
    OCR
}
