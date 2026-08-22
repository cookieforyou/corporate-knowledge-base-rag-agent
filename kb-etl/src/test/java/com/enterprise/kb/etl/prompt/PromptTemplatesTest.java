package com.enterprise.kb.etl.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptTemplates（解析链）契约测试（4.8 Git Ops 外部化，簇⑦ 批2）：
 * 语境增强模板 %s 双槽（概要, 片段）契约钉死 + 渲染产物形态。
 */
class PromptTemplatesTest {

    @Test
    void contextEnrichmentPromptCarriesTwoSlotsInOrder() {
        assertThat(PromptTemplates.CONTEXT_ENRICHMENT_PROMPT)
            .contains("<document>")
            .contains("<chunk>")
            .contains("50-100 字");
    }

    @Test
    void formattedFillsExcerptAndChunkInDeclaredOrder() {
        String rendered = PromptTemplates.CONTEXT_ENRICHMENT_PROMPT.formatted("概要文本", "片段文本");

        // 槽位顺序 = 概要先、片段后（与 ContextualEnrichmentTransformer 调用点契约一致）
        assertThat(rendered.indexOf("概要文本")).isLessThan(rendered.indexOf("片段文本"));
        assertThat(rendered).contains("<document>\n概要文本");
        assertThat(rendered).contains("<chunk>\n片段文本");
    }
}
