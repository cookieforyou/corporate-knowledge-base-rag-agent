package com.enterprise.kb.infrastructure.parsing;

import org.junit.jupiter.api.Test;

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
}
