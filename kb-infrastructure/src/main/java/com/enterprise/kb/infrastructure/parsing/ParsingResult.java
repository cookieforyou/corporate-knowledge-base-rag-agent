package com.enterprise.kb.infrastructure.parsing;

/**
 * 深度解析结果（设计文档 9.1）
 *
 * @param markdown    结构化正文：Markdown 文本 + 表格保留 HTML 块（{@code <table>}），
 *                    交由 HtmlProtectingSplitter（2.3）保护式切分
 * @param tableCount  表格数（运维统计 + 路由质量回溯）
 * @param imageCount  图片数
 * @param pageCount   页数（落库 kb_document.page_count）
 */
public record ParsingResult(String markdown, int tableCount, int imageCount, int pageCount) {

    public static final ParsingResult EMPTY = new ParsingResult("", 0, 0, 0);
}
