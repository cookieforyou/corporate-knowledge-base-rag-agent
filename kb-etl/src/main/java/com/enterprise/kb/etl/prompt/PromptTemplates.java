package com.enterprise.kb.etl.prompt;

/**
 * 解析链（ETL）Prompt 单一事实源（4.8 Git Ops 外部化，簇⑦ 批2，2026-08-22）。
 *
 * <p><b>Git Ops 纪律</b>：本类是解析链（kb-etl）全部 Prompt 模板的唯一收编处——
 * 新增/修改/删除模板一律在本类操作，消费方经 {@code PromptTemplates.XXX}
 * 常量引用；{@code git log} 即版本历史，{@code git diff} 即变更审计。
 *
 * <p><b>模块边界</b>：kb-etl 不依赖 kb-ai-core（依赖链
 * kb-etl → kb-infrastructure → kb-domain），对话链 Prompt 收编于
 * {@code com.enterprise.kb.ai.prompt.PromptTemplates}；本类仅承载解析链模板。
 */
public final class PromptTemplates {

    private PromptTemplates() {
        // 工具类禁止实例化
    }

    /**
     * 语境增强模板（§9.5，2.4 ContextualEnrichmentTransformer）：文档概要 + 片段
     * 双参经 String.formatted 填充（%s 双槽，顺序 = 概要, 片段）。
     * 产出 50-100 字位置与作用说明，落 chunk 增强前缀（【上下文】）。
     */
    public static final String CONTEXT_ENRICHMENT_PROMPT = """
        <document>
        %s
        </document>

        请用 50-100 字说明下面这个片段在文档中的位置与作用（涉及什么主题、与上下文的关系），
        只输出说明文本：
        <chunk>
        %s
        </chunk>
        """;
}
