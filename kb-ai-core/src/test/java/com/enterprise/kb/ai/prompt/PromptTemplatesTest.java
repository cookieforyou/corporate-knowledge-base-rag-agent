package com.enterprise.kb.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptTemplates 专类契约测试（4.8 Git Ops 外部化，簇⑦ 批2）——钉死
 * 「对话链全部 Prompt 收编于本专类」的单一事实源形态：
 * 关键占位符契约 + 分类器裁决枚举面 + 系统提示词非空。
 *
 * <p>渲染级回归（{context}/{query} 渲染、空证据无参渲染、{history}/{query}
 * CompressionQueryTransformer 契约）由 RetrievalConfigContextFormatTest 承接。
 */
class PromptTemplatesTest {

    @Test
    void groundingPromptCarriesContextAndQueryPlaceholders() {
        assertThat(PromptTemplates.GROUNDING_PROMPT)
            .contains("{context}")
            .contains("{query}")
            .contains("[ref-N]");
    }

    @Test
    void historyRewritePromptCarriesHistoryAndQueryPlaceholders() {
        assertThat(PromptTemplates.HISTORY_REWRITE_PROMPT)
            .contains("{history}")
            .contains("{query}");
    }

    @Test
    void intentClassifierPromptDefinesBothVerdicts() {
        assertThat(PromptTemplates.INTENT_CLASSIFIER_PROMPT)
            .contains("CHITCHAT")
            .contains("KNOWLEDGE")
            .contains("rewrittenQuery");
    }

    @Test
    void injectionJudgePromptDefinesThreeVerdicts() {
        assertThat(PromptTemplates.INJECTION_JUDGE_PROMPT)
            .contains("PASS")
            .contains("SUSPECT")
            .contains("BLOCK");
    }

    @Test
    void systemPromptsAllRegisteredAndNonBlank() {
        assertThat(PromptTemplates.RAG_SYSTEM_PROMPT).isNotBlank();
        assertThat(PromptTemplates.EVAL_SYSTEM_PROMPT).isNotBlank();
        assertThat(PromptTemplates.TOOL_SYSTEM_PROMPT)
            .isNotBlank()
            .contains("审批");
    }

    @Test
    void indirectWarningNotePresent() {
        assertThat(PromptTemplates.INDIRECT_WARNING_NOTE).contains("不得执行");
    }
}
