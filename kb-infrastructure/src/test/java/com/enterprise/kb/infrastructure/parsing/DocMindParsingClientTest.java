package com.enterprise.kb.infrastructure.parsing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocMindParsingClient 单测：llmResult 代码围栏剥离（表格 HTML 提取的前置处理）
 *
 * <p>背景（2026-08-03 E2E 缺陷）：文档解析大模型版的表格 HTML 需提交时开启
 * OutputHtmlTable，内容存放在表格版面块的 llmResult 字段，实测以
 * {@code ```html ... ```} 围栏包裹——不剥离则围栏标记混入正文流。
 */
class DocMindParsingClientTest {

    @Test
    void fencedHtmlTable_unwrapped() {
        String fenced = "```html\n<table><tr><td>增值税专用发票</td></tr></table>\n```";

        assertThat(DocMindParsingClient.stripCodeFence(fenced))
            .isEqualTo("<table><tr><td>增值税专用发票</td></tr></table>");
    }

    @Test
    void fencedMarkdown_unwrapped() {
        String fenced = "```markdown\n# 标题\n正文\n```";

        assertThat(DocMindParsingClient.stripCodeFence(fenced)).isEqualTo("# 标题\n正文");
    }

    @Test
    void bareHtml_returnedAsIs() {
        String bare = "<table><tr><td>13%</td></tr></table>";

        assertThat(DocMindParsingClient.stripCodeFence(bare)).isEqualTo(bare);
    }

    @Test
    void unclosedFence_returnedAsIs_downstreamTolerates() {
        String unclosed = "```html\n<table><tr><td>未闭合";

        assertThat(DocMindParsingClient.stripCodeFence(unclosed)).isEqualTo(unclosed);
    }

    @Test
    void nullInput_returnsEmpty() {
        assertThat(DocMindParsingClient.stripCodeFence(null)).isEmpty();
    }

    @Test
    void emptyFence_returnsEmpty() {
        assertThat(DocMindParsingClient.stripCodeFence("```\n```")).isEmpty();
    }

    // ── layoutContent：版面块正文提取 ──

    @Test
    void tableLayout_prefersLlmResultHtml() {
        Map<String, Object> layout = new HashMap<>();
        layout.put("llmResult", "```html\n<table><tr><td>13%</td></tr></table>\n```");
        layout.put("markdownContent", "| 管道符降级文本 |");
        layout.put("text", "纯文本降级");

        assertThat(DocMindParsingClient.layoutContent(layout, "table"))
            .isEqualTo("<table><tr><td>13%</td></tr></table>");
    }

    @Test
    void tableLayout_withoutLlmResult_fallsBackToMarkdownContent() {
        Map<String, Object> layout = new HashMap<>();
        layout.put("markdownContent", "| 名称 | 类型 |\n| --- | --- |");
        layout.put("text", "名称 类型");

        assertThat(DocMindParsingClient.layoutContent(layout, "table"))
            .contains("| 名称 | 类型 |");
    }

    @Test
    void textLayout_prefersMarkdownContentOverText() {
        Map<String, Object> layout = new HashMap<>();
        layout.put("markdownContent", "## 调用方式\n正文段落");
        layout.put("text", "调用方式 正文段落");

        assertThat(DocMindParsingClient.layoutContent(layout, "text"))
            .isEqualTo("## 调用方式\n正文段落");
    }

    // ── toPageSegments：0 起 pageNum → 1 起 PageSegment ──

    @Test
    void pageSegments_zeroBasedToOneBased_sortedAndBlankDropped() {
        Map<Integer, StringBuilder> byPage = new TreeMap<>();
        byPage.put(2, new StringBuilder("第三页正文"));
        byPage.put(0, new StringBuilder("第一页正文"));
        byPage.put(1, new StringBuilder("   "));   // 空页剔除

        List<ParsingResult.PageSegment> pages = DocMindParsingClient.toPageSegments(byPage);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).isEqualTo(new ParsingResult.PageSegment(1, "第一页正文"));
        assertThat(pages.get(1)).isEqualTo(new ParsingResult.PageSegment(3, "第三页正文"));
    }
}
