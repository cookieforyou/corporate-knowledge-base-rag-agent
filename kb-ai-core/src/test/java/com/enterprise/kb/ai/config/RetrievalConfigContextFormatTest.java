package com.enterprise.kb.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据编号化格式器测试（v2.15，2026-08-09）——修复引用编号漂移缺陷：
 * ContextualQueryAugmenter 默认拼接不编号，模型引用 [ref-N] 无锚点
 * （抄文档正文圈号/越界编号，前端 ASCII 正则不匹配致不可点）。
 */
class RetrievalConfigContextFormatTest {

    @Test
    void prefixesEachDocumentWithOneBasedRefLabelInOrder() {
        List<Document> documents = List.of(
            Document.builder().text("防腐层是隔离层").build(),
            Document.builder().text("限界上下文定义语义边界").build());

        String context = RetrievalConfig.formatNumberedContext(documents);

        assertThat(context).isEqualTo(
            "[ref-1]\n防腐层是隔离层\n\n[ref-2]\n限界上下文定义语义边界\n\n");
    }

    @Test
    void emptyDocumentListRendersEmptyContext() {
        assertThat(RetrievalConfig.formatNumberedContext(List.of())).isEmpty();
    }

    @Test
    void documentContainingCircledDigitsGetsItsOwnRefLabel() {
        // 缺陷复现场景：文档正文自带圈号标题（DDD 文档「#### ⑤ 防腐层」），
        // 编号行提供独立 ASCII 锚点，模型无需从正文猜编号
        Document doc = Document.builder().text("#### ⑤ 防腐层（ACL）\n防腐层是 DDD 模式").build();

        String context = RetrievalConfig.formatNumberedContext(List.of(doc));

        assertThat(context).startsWith("[ref-1]\n#### ⑤ 防腐层");
    }

    @Test
    void groundingPromptRendersWithNumberedContextAndQuery() {
        PromptTemplate template = new PromptTemplate(RetrievalConfig.GROUNDING_PROMPT);
        String context = RetrievalConfig.formatNumberedContext(
            List.of(Document.builder().text("防腐层是隔离层").build()));

        String rendered = template.render(Map.of("context", context, "query", "防腐层有什么作用"));

        assertThat(rendered)
            .contains("[ref-1]\n防腐层是隔离层")
            .contains("防腐层有什么作用")
            .contains("禁止使用 ①②③ 等圈号");
    }
}
