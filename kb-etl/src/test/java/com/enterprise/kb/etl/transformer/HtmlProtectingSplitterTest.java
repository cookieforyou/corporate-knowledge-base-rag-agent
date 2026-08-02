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
}
