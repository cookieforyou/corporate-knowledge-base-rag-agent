package com.enterprise.kb.etl.transformer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HtmlProtectingSplitter 单测（2.3）：保护块独立成 Chunk + 快速路径零行为变化
 */
class HtmlProtectingSplitterTest {

    private final HtmlProtectingSplitter splitter = new HtmlProtectingSplitter();

    @Test
    void textOnlyDocument_takesFastPath_noChunkTypeMarker() {
        String text = "领域驱动设计是一种软件设计方法论。".repeat(80);   // 纯文本无保护标签

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).isNotEmpty();
        // 快速路径产物不携带 chunk_type 元数据（落库时缺省 TEXT，Phase 1 行为不变）
        assertThat(chunks).noneMatch(c -> c.getMetadata().containsKey("chunk_type"));
    }

    @Test
    void tableBlock_becomesIndependentTableChunk_withOriginalHtml() {
        String table = """
            <table><tr><th>发票类型</th><th>税率</th></tr>
            <tr><td>增值税专用发票</td><td>13%</td></tr>
            <tr><td>增值税普通发票</td><td>6%</td></tr></table>""";
        String docText = "以下是发票税率表：\n" + table + "\n表格之后还有正文内容。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(docText)));

        List<Document> tableChunks = chunks.stream()
            .filter(c -> "TABLE".equals(c.getMetadata().get("chunk_type"))).toList();
        assertThat(tableChunks).hasSize(1);

        Document tableChunk = tableChunks.get(0);
        assertThat(tableChunk.getText()).contains("<table>").contains("增值税专用发票");
        // original_html 保留完整结构（落库写 kb_chunk.original_content）
        assertThat(tableChunk.getMetadata().get("original_html").toString()).contains("<table>");
        // 表格前后的文本仍正常切分
        assertThat(chunks.stream().filter(c -> !c.getMetadata().containsKey("chunk_type"))
            .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void smallTable_degradesToText_noTableChunk() {
        String docText = "前言。\n<table><tr><td>短</td></tr></table>\n后续正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(docText)));

        assertThat(chunks).noneMatch(c -> "TABLE".equals(c.getMetadata().get("chunk_type")));
    }

    @Test
    void imageBlock_becomesImageChunk_withOuterHtml() {
        String docText = "图示说明：\n<img src=\"arch.png\" alt=\"架构图\">\n" + "正文内容段落。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(docText)));

        List<Document> imageChunks = chunks.stream()
            .filter(c -> "IMAGE".equals(c.getMetadata().get("chunk_type"))).toList();
        assertThat(imageChunks).hasSize(1);
        assertThat(imageChunks.get(0).getMetadata().get("original_html").toString())
            .contains("arch.png");
    }

    @Test
    void preservesSourceDocumentMetadata() {
        String docText = "<table><tr><td>足够长的表格内容以超过小表格阈值</td></tr></table>";
        Document source = Document.builder().text(docText)
            .metadata(Map.of("doc_id", "d-1")).build();

        List<Document> chunks = splitter.apply(List.of(source));

        assertThat(chunks).allMatch(c -> "d-1".equals(c.getMetadata().get("doc_id")));
    }

    // ── 簇④ A4：heading 路径跟踪 ──

    @Test
    void markdownHeadings_injectHeadingPathPerSection() {
        String text = "# 产品概述\n" + "这是产品概述章节的正文内容。".repeat(30)
            + "\n## 定价\n" + "这是定价章节的正文内容说明。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).isNotEmpty();
        // 各 chunk 按所属章节携带 heading_path（「L1 > L2」层级拼接）
        assertThat(chunks).allSatisfy(c ->
            assertThat(c.getMetadata()).containsKey(HtmlProtectingSplitter.HEADING_PATH_KEY));
        assertThat(chunks.stream().filter(c -> c.getText().contains("产品概述章节")).toList())
            .allSatisfy(c -> assertThat(c.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY))
                .isEqualTo("产品概述"));
        assertThat(chunks.stream().filter(c -> c.getText().contains("定价章节")).toList())
            .allSatisfy(c -> assertThat(c.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY))
                .isEqualTo("产品概述 > 定价"));
    }

    @Test
    void markdownHeadingText_retainedInChunkContent() {
        String text = "# 章节标题\n" + "章节正文内容段落。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        // 标题文字保留在 chunk 正文首部（BM25/向量化可检索）
        assertThat(chunks.get(0).getText()).contains("章节标题");
    }

    @Test
    void tableChunk_carriesActiveHeadingPath() {
        String table = "<table><tr><th>项目</th><th>金额</th><th>说明</th></tr>"
            + "<tr><td>基础服务费</td><td>1000 元</td><td>按年收取</td></tr>"
            + "<tr><td>增值服务费</td><td>2000 元</td><td>可选购</td></tr></table>";
        String text = "# 合同条款\n## 费用明细\n" + table + "\n" + "后续正文。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        Document tableChunk = chunks.stream()
            .filter(c -> "TABLE".equals(c.getMetadata().get("chunk_type")))
            .findFirst().orElseThrow();
        assertThat(tableChunk.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY))
            .isEqualTo("合同条款 > 费用明细");
    }

    @Test
    void noHeadings_noHeadingPathKey() {
        String table = "<table><tr><td>足够长的表格内容以超过小表格阈值</td></tr></table>";
        String text = "正文前言。\n" + table + "\n" + "后续正文。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(c ->
            c.getMetadata().containsKey(HtmlProtectingSplitter.HEADING_PATH_KEY));
    }

    @Test
    void angleBracketsInCode_preservedOnHeadingOnlyPath() {
        // 仅标题无保护标签 → 纯行扫描不经 JSoup，代码尖括号不丢
        String text = "# 开发指南\n" + "使用 List<String> 泛型声明集合。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).allSatisfy(c -> assertThat(c.getText()).contains("List<String>"));
    }

    @Test
    void headingStack_newTopLevelClearsDeeperLevels() {
        String[] headings = new String[7];
        HtmlProtectingSplitter.setHeading(headings, 1, "A");
        HtmlProtectingSplitter.setHeading(headings, 2, "B");
        assertThat(HtmlProtectingSplitter.headingPathOf(headings)).isEqualTo("A > B");

        HtmlProtectingSplitter.setHeading(headings, 1, "C");   // 新 h1 → h2 失效
        assertThat(HtmlProtectingSplitter.headingPathOf(headings)).isEqualTo("C");
    }
}
