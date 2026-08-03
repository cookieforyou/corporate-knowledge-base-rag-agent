package com.enterprise.kb.infrastructure.parsing;

import java.util.List;

/**
 * 深度解析结果（设计文档 9.1）
 *
 * @param markdown    结构化正文（全文拼接视图）：Markdown 文本 + 表格保留 HTML 块
 *                    （{@code <table>}），交由 HtmlProtectingSplitter（2.3）保护式切分
 * @param pages       按页切分的正文（pageNum 从 1 起）；切分器按页独立切分并以
 *                    page_number 元数据下传，落库 kb_chunk.page_num。
 *                    空列表表示解析后端无页级信息（整篇按 markdown 处理）
 * @param tableCount  表格数（运维统计 + 路由质量回溯）
 * @param imageCount  图片数
 * @param pageCount   页数（落库 kb_document.page_count）
 */
public record ParsingResult(String markdown, List<PageSegment> pages,
                            int tableCount, int imageCount, int pageCount) {

    /** 单页正文块（pageNum 从 1 起，与 kb_chunk.page_num 语义一致） */
    public record PageSegment(int pageNum, String content) {}

    public static final ParsingResult EMPTY = new ParsingResult("", List.of(), 0, 0, 0);

    /** 兼容构造：无页级信息的解析结果（整篇 markdown，页码不下传） */
    public ParsingResult(String markdown, int tableCount, int imageCount, int pageCount) {
        this(markdown, List.of(), tableCount, imageCount, pageCount);
    }
}
