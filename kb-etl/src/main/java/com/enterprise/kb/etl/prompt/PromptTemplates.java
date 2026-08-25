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

    /**
     * 知识图谱实体关系抽取模板（簇④ GraphRAG，5.1）：目标片段 + 前后相邻片段
     * 三槽经 String.formatted 填充（%s 三槽，顺序 = 相邻上文, 目标片段, 相邻下文；
     * 窗口语境缓解切分边界切断实体陈述）。产出经 ChatClient {@code .entity()}
     * 映射为结构化抽取结果——模板本体只描述抽取语义，输出格式指令由
     * 结构化输出转换器自动附加。
     */
    public static final String KG_EXTRACTION_PROMPT = """
        你是企业知识库的实体关系抽取器。阅读目标片段（附前后相邻片段仅作语境参考），
        抽取目标片段中的实体与实体间关系。

        【相邻上文】
        %s

        【目标片段】
        %s

        【相邻下文】
        %s

        【抽取要求】
        1. 实体：名称取片段中的规范全称；类型限 PERSON / ORG / PRODUCT / CONCEPT / LOCATION / TECH / EVENT / OTHER 之一；描述 ≤100 字，须携带可区分信息（是什么、关键属性或作用）
        2. 关系：仅抽取目标片段中明确陈述的关系；源与目标必须是本轮已抽取的实体名；关系类型用简短大写英文标识（如 WORKS_AT / PART_OF / DEPENDS_ON / LOCATED_IN / PRODUCED_BY / RELATED_TO）；描述 ≤50 字
        3. 宁缺毋滥：不确定的不抽取；代词、片段外信息、泛化词（如"系统""用户"）不作为实体
        4. 目标片段无明确实体时返回空数组
        """;
}
