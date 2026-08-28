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

    /**
     * 七族系结构判据完备性（§12.11 载荷纪律：仅结构描述零字面载荷）——
     * 族系名与 advisor 输出指令枚举面一一对应，缺一即判据面漂移。
     */
    @Test
    void injectionJudgePromptCarriesAllSevenFamilyCriteria() {
        assertThat(PromptTemplates.INJECTION_JUDGE_PROMPT)
            .contains("指令覆盖族")
            .contains("角色劫持族")
            .contains("敏感信息套取族")
            .contains("编码混淆族")
            .contains("多语种族")
            .contains("越狱引导族")
            .contains("工具诱导族");
    }

    /**
     * 簇② 批5 路径 b 判据校准契约钉死：【剥壳判据】在场（包裹手段不改变裁决）
     * ∧ 保守纪律保留（拿不准倾向 PASS）——两翼缺一即校准回退或过度收紧。
     */
    @Test
    void injectionJudgePromptCarriesShellPeelingCriteriaWithConservativeDiscipline() {
        assertThat(PromptTemplates.INJECTION_JUDGE_PROMPT)
            .contains("【剥壳判据】")
            .contains("包裹手段不改变裁决")
            .contains("突破安全策略")
            .contains("拿不准时：倾向正常裁 PASS");
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
