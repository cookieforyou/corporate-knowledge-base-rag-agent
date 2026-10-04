package com.enterprise.kb.etl.transformer;

import com.enterprise.kb.commons.constant.Constants;
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
        assertThat(chunks).noneMatch(c -> c.getMetadata().containsKey(Constants.Retrieval.META_CHUNK_TYPE));
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
            .filter(c -> "TABLE".equals(c.getMetadata().get(Constants.Retrieval.META_CHUNK_TYPE))).toList();
        assertThat(tableChunks).hasSize(1);

        Document tableChunk = tableChunks.get(0);
        assertThat(tableChunk.getText()).contains("<table>").contains("增值税专用发票");
        // original_html 保留完整结构（落库写 kb_chunk.original_content）
        assertThat(tableChunk.getMetadata().get("original_html").toString()).contains("<table>");
        // 表格前后的文本仍正常切分
        assertThat(chunks.stream().filter(c -> !c.getMetadata().containsKey(Constants.Retrieval.META_CHUNK_TYPE))
            .count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void smallTable_degradesToText_noTableChunk() {
        String docText = "前言。\n<table><tr><td>短</td></tr></table>\n后续正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(docText)));

        assertThat(chunks).noneMatch(c -> "TABLE".equals(c.getMetadata().get(Constants.Retrieval.META_CHUNK_TYPE)));
    }

    @Test
    void imageBlock_becomesImageChunk_withOuterHtml() {
        String docText = "图示说明：\n<img src=\"arch.png\" alt=\"架构图\">\n" + "正文内容段落。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(docText)));

        List<Document> imageChunks = chunks.stream()
            .filter(c -> "IMAGE".equals(c.getMetadata().get(Constants.Retrieval.META_CHUNK_TYPE))).toList();
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

    // ── 冲刺簇④ A4：heading 路径跟踪 ──

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
            .filter(c -> "TABLE".equals(c.getMetadata().get(Constants.Retrieval.META_CHUNK_TYPE)))
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

    // ── 修复批 1（9.2 v2.22）：统一入口判据 + 代码围栏屏蔽 + 空标题守卫 ──

    /** 足够长的真表格（超过小表格退化阈值） */
    private static final String LONG_TABLE =
        "<table><tr><th>级别</th><th>名称</th><th>判定标准</th></tr>"
            + "<tr><td>P1</td><td>重大事件</td><td>核心系统中断超过 2 小时</td></tr>"
            + "<tr><td>P2</td><td>较大事件</td><td>单部门业务中断超过 4 小时</td></tr></table>";

    private static boolean isTableChunk(Document c) {
        return "TABLE".equals(c.getMetadata().get(Constants.Retrieval.META_CHUNK_TYPE));
    }

    private static String headingPathOf(Document c) {
        return String.valueOf(c.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY));
    }

    @Test
    void pureHtmlHeadings_trackHeadingPath_withoutProtectedTags() {
        // 无 table/img 的纯 HTML 标题文档：旧实现走快速路径 → heading_path 全丢且标签进正文
        String text = "<h1>产品手册</h1>\n<p>" + "这是第一章的正文内容说明。".repeat(30) + "</p>\n"
            + "<h2>定价</h2>\n<p>" + "这是定价章节的正文内容说明。".repeat(30) + "</p>";

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).hasSize(2);
        assertThat(headingPathOf(chunks.get(0))).isEqualTo("产品手册");
        assertThat(headingPathOf(chunks.get(1))).isEqualTo("产品手册 > 定价");
        assertThat(chunks).allSatisfy(c -> assertThat(c.getText()).doesNotContain("<h1>").doesNotContain("<p>"));
    }

    @Test
    void fencedCodeBlock_headingInsideIgnored_fenceTextPreserved() {
        String text = "## 注意事项\n" + "正文说明内容。".repeat(30)
            + "\n```bash\n# 这不是标题，是注释\n" + "echo hello world; ".repeat(10) + "\n```\n"
            + "代码块之后的正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("这不是标题"));
        assertThat(chunks.stream().filter(c -> c.getText().contains("是注释")).toList())
            .allSatisfy(c -> assertThat(headingPathOf(c)).isEqualTo("注意事项"));
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("```bash"))).isTrue();
    }

    @Test
    void fencedCodeBlock_onlyFakeHeading_noHeadingPath() {
        String text = "安装说明如下：\n```bash\n# 安装依赖\n" + "echo installing; ".repeat(20) + "\n```\n"
            + "其余正文内容说明。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(c -> c.getMetadata().containsKey(HtmlProtectingSplitter.HEADING_PATH_KEY));
    }

    @Test
    void fencedTableSample_notPromotedToTableChunk() {
        String text = "# HTML 教程\n" + "教程正文说明。".repeat(30)
            + "\n```html\n<table>\n<tr><th>字段名</th><th>类型</th><th>说明</th></tr>\n"
            + "<tr><td>tenant_id</td><td>varchar</td><td>租户标识字段</td></tr>\n</table>\n```\n"
            + "教程结尾正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(HtmlProtectingSplitterTest::isTableChunk);
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("<table>"))).isTrue();
    }

    @Test
    void fencedHtmlHeading_ignored_whenRealTablePresent() {
        String text = "# HTML 教程\n" + "教程正文说明。".repeat(30) + "\n" + LONG_TABLE
            + "\n以下是示例代码：\n```html\n<h1>示例标题</h1>\n<p>示例段落</p>\n```\n"
            + "教程结尾正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks.stream().filter(HtmlProtectingSplitterTest::isTableChunk)).hasSize(1);
        assertThat(chunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("示例标题"));
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("<h1>示例标题</h1>"))).isTrue();
    }

    @Test
    void pseudoTag_staysOnFastPath_noTagStripping() {
        String text = "<tableau>这是一个伪标签段落，" + "正文内容说明。".repeat(40) + "</tableau>";

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(HtmlProtectingSplitterTest::isTableChunk);
        assertThat(chunks.get(0).getText()).contains("<tableau>");
    }

    @Test
    void unclosedPseudoTag_noChunkLoss() {
        // 旧实现：宽松判据把 <imgs 误判为 <img → 走 JSoup → 未闭合标签吞掉其后全文 → 0 chunk 静默丢失
        String text = "纯文本说明。\n```\n# 注释\n<imgs 伪标签\n```\n" + "尾部文本。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("尾部文本"))).isTrue();
    }

    @Test
    void indentedAtxHeading_upToThreeSpaces_recognized() {
        String text = "   # 缩进三个空格的一级标题\n" + "章节正文内容说明。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(headingPathOf(chunks.get(0))).isEqualTo("缩进三个空格的一级标题");
    }

    @Test
    void fourSpaceIndentedHash_notHeading() {
        String text = "    # 四空格缩进不是标题\n" + "章节正文内容说明。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(c -> c.getMetadata().containsKey(HtmlProtectingSplitter.HEADING_PATH_KEY));
    }

    @Test
    void blankHtmlHeading_doesNotSplitSection() {
        String text = LONG_TABLE + "\n<h1>总则</h1>\n<p>" + "总则正文内容说明。".repeat(30)
            + "</p>\n<h2></h2>\n<p>" + "后续正文内容说明。".repeat(30) + "</p>";

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks.stream().filter(HtmlProtectingSplitterTest::isTableChunk)).hasSize(1);
        assertThat(chunks.stream().filter(c -> !isTableChunk(c)).count()).isEqualTo(1);   // 旧实现被空标题劈成 2 段
    }

    @Test
    void nbspOnlyHtmlHeading_noBoundaryAndNoNbspInPath() {
        String text = LONG_TABLE + "\n<h1>总则</h1>\n<p>" + "总则正文内容说明。".repeat(30)
            + "</p>\n<h2>&nbsp;</h2>\n<p>" + "后续正文内容说明。".repeat(30) + "</p>";

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks.stream().filter(c -> !isTableChunk(c)).count()).isEqualTo(1);
        assertThat(chunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("\u00a0"));
    }

    @Test
    void htmlCommentWithTable_keepsCommentOnMarkdownPath() {
        String text = "# 说明\n" + "正文内容说明。".repeat(30)
            + "\n<!-- 示例：<table><tr><td>x</td></tr></table> -->\n" + "结尾正文。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(HtmlProtectingSplitterTest::isTableChunk);
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("示例："))).isTrue();
    }

    @Test
    void tildeAndLongFence_alsoShieldHeadings() {
        String tilde = "# 真标题\n" + "正文说明。".repeat(30) + "\n~~~\n# 不是标题\n"
            + "code(); ".repeat(20) + "\n~~~\n" + "结尾正文。".repeat(30);
        List<Document> tildeChunks = splitter.apply(List.of(new Document(tilde)));
        assertThat(tildeChunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("不是标题"));

        String longFence = "# 标题\n" + "正文说明。".repeat(30) + "\n````markdown\n```\n# 内层不是标题\n```\n````\n"
            + "结尾正文。".repeat(30);
        List<Document> longFenceChunks = splitter.apply(List.of(new Document(longFence)));
        assertThat(longFenceChunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("内层不是标题"));
        assertThat(longFenceChunks.stream().anyMatch(c -> c.getText().contains("# 内层不是标题"))).isTrue();
    }

    @Test
    void unclosedFence_extendsToEnd_noFakeHeadingNoTableChunk() {
        String text = "前言说明。\n```bash\n# 注释\n"
            + "<table><tr><td>示例单元格足够长文本</td></tr></table>\n" + "尾部内容。".repeat(30);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        assertThat(chunks).noneMatch(c -> c.getMetadata().containsKey(HtmlProtectingSplitter.HEADING_PATH_KEY));
        assertThat(chunks).noneMatch(HtmlProtectingSplitterTest::isTableChunk);
    }

    @Test
    void corpusShape_realTableAndFencedSample_coexist() {
        // 语料形态回归：Markdown 真标题 + 真表格（保护）+ 围栏内假标题/假表格（屏蔽）
        String text = "# 管理办法\n" + "总则正文内容。".repeat(40)
            + "\n## 事件分级\n" + LONG_TABLE
            + "\n```bash\n# 安装依赖\n<table><tr><td>代码里的表格单元格文本足够长</td></tr></table>\n```\n"
            + "## 附则\n" + "附则正文内容。".repeat(40);

        List<Document> chunks = splitter.apply(List.of(new Document(text)));

        List<Document> tableChunks = chunks.stream().filter(HtmlProtectingSplitterTest::isTableChunk).toList();
        assertThat(tableChunks).hasSize(1);   // 只有真表格成 TABLE chunk，围栏样例不成
        assertThat(headingPathOf(tableChunks.get(0))).isEqualTo("管理办法 > 事件分级");
        assertThat(chunks.stream().anyMatch(c -> c.getText().contains("代码里的表格"))).isTrue();
        assertThat(chunks).allSatisfy(c -> assertThat(headingPathOf(c)).doesNotContain("安装依赖"));
        assertThat(chunks.stream().filter(c -> c.getText().contains("附则正文")).toList())
            .allSatisfy(c -> assertThat(headingPathOf(c)).isEqualTo("管理办法 > 附则"));
    }
}
