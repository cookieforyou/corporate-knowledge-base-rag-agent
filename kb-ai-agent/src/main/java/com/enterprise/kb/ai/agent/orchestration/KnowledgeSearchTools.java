package com.enterprise.kb.ai.agent.orchestration;

import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识检索子代理工具（簇⑤ 5.3 批2）——检索管线同构 McpKnowledgeTools（改写 →
 * 双路[+Graph]召回 → RRF → 重排，不经主答 LLM），差异仅在身份面：
 * MCP 版经 McpIdentityGuard 捕获请求线程 JWT，本类经 ToolContext 下传的
 * RetrievalContext（TaskTool 身份链）——复用 McpKnowledgeTools 实例不可行，
 * 身份物化通道不同（N3 核验结论）。
 *
 * <p><b>安全语义</b>：租户过滤经 RetrievalContext 参数链（fail-closed——
 * 有 ctx 无租户返回空检索结果，HybridDocumentRetriever 防御纵深）；
 * getDocument 跨租户/不存在一律 KB_DOC_NOT_FOUND（不泄露存在性）。
 * 限流/审计不在此层——主链 RateLimit/Audit 已计账（mode=agent 单请求），
 * 委派记录经 TaskTool 写 RetrievalContext.ToolCall 快照。
 *
 * <p>检索查询改写挂备模型（v2.83 轻任务纪律）；本工具自身零 LLM 调用。
 */
@Component
public class KnowledgeSearchTools {

    private final HybridDocumentRetriever hybridRetriever;
    private final RerankDocumentPostProcessor rerankPostProcessor;
    private final QueryTransformer rewriteQueryTransformer;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final JsonMapper jsonMapper;
    private final int documentMaxChunks;
    private final int maxChars;
    private final int maxSearches;

    public KnowledgeSearchTools(HybridDocumentRetriever hybridRetriever,
                                RerankDocumentPostProcessor rerankPostProcessor,
                                @Qualifier("rewriteQueryTransformer") QueryTransformer rewriteQueryTransformer,
                                KbDocumentRepository documentRepository,
                                KbChunkRepository chunkRepository,
                                JsonMapper jsonMapper,
                                @Value("${rag.orchestrator.knowledge.max-chunks:30}") int documentMaxChunks,
                                @Value("${rag.orchestrator.knowledge.max-chars:400}") int maxChars,
                                @Value("${rag.orchestrator.knowledge.max-searches:6}") int maxSearches) {
        this.hybridRetriever = hybridRetriever;
        this.rerankPostProcessor = rerankPostProcessor;
        this.rewriteQueryTransformer = rewriteQueryTransformer;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.jsonMapper = jsonMapper;
        this.documentMaxChunks = Math.max(1, documentMaxChunks);
        this.maxChars = Math.max(50, maxChars);
        this.maxSearches = Math.max(1, maxSearches);
    }

    /**
     * 知识库混合检索（向量 + BM25 [+Graph] + 重排序，零 LLM）——子代理取证主路径。
     *
     * <p><b>E2E 热修五治理三件</b>：
     * ① <b>载荷截断</b>——正文每条截断至 {@code max-chars}（高频检索 = 摘要级载荷，
     * 全文经 getDocument 深读）；子代理同上下文多轮工具循环下全文载荷滚胀数十万
     * token，指令被淹没致检索循环不收敛（40 次检索实证）。
     * ② <b>检索预算硬闸</b>——单请求（跨委派 + 超时弃任务同计数）超过
     * {@code max-searches} 次即返回空结果 + 停止提示；prompt 收敛纪律对膨胀后的
     * 上下文是概率性约束，预算闸是确定性兜底（与 TaskTool 委派闸同构双层）。
     * ③ <b>trace 隔离</b>——检索管线喂隔离 ctx（仅拷贝租户身份），多路命中不再
     * 写主请求 trace（agent 链审计行 retrieved_chunks 曾累积 883 条/唯一 43）；
     * 溯源语义改经 {@code search:knowledge} ToolCall 快照承载（query 摘要入审计）。
     */
    @Tool(description = "在企业知识库中检索与问题最相关的文档片段（混合检索+重排序）。"
        + "适用于查找制度、规范、流程、事实等知识依据；返回 hits 含文件名/页码/标题路径/"
        + "正文摘要（截断），note 携带检索预算提示——note 要求停止检索时必须立即停止并"
        + "基于已有结果归纳，需要完整上下文时改用 getDocument")
    public SearchOutcome searchKnowledge(
            @ToolParam(description = "自然语言检索问题（自包含，可独立理解）") String query,
            ToolContext toolContext) {
        RetrievalContext ctx = requireContext(toolContext);
        int executed = countExecutedSearches(ctx);
        if (executed >= maxSearches) {
            return new SearchOutcome(List.of(), "检索次数已达上限（" + maxSearches
                + " 次/请求）。不要再调用检索工具，立即基于已获得的检索结果归纳回答。");
        }
        // 检索进度推送（簇⑥ 体验批3，PROGRESS 帧）：开始即推——单次检索路径
        // （改写+双路召回+重排）数秒静默期前端可感知；监听器缺席 no-op
        ctx.emitProgress("retrieval",
            "知识检索 " + (executed + 1) + "/" + maxSearches + " 次：" + abbreviate(query));

        Query rewritten = rewriteQueryTransformer.apply(new Query(query));
        RetrievalContext isolated = isolatedContext(ctx);
        Map<String, Object> queryContext = Map.of(RetrievalContext.CONTEXT_KEY, isolated);
        List<Document> fused = hybridRetriever.retrieve(
            Query.builder().text(rewritten.text()).context(queryContext).build());
        List<Document> finals = rerankPostProcessor.process(
            Query.builder().text(rewritten.text()).context(queryContext).build(), fused);

        List<SearchHit> hits = new ArrayList<>(finals.size());
        int rank = 0;
        for (Document doc : finals) {
            rank++;
            Map<String, Object> meta = doc.getMetadata();
            hits.add(new SearchHit(doc.getId(),
                asString(meta.get("file_name")),
                asString(meta.get("heading_path")),
                meta.get("page_num") instanceof Number n ? n.intValue() : null,
                truncate(doc.getText()),
                rank));
        }
        ctx.addToolCall(new RetrievalContext.ToolCall("search:knowledge",
            RetrievalContext.ToolCall.STATUS_EXECUTED, null, "检索: " + abbreviate(query)));
        int remaining = maxSearches - executed - 1;
        return new SearchOutcome(hits,
            "检索预算剩余 " + remaining + "/" + maxSearches + " 次，证据足够时请立即归纳回答");
    }

    /**
     * 文档全文读取（元信息 + 存活 chunk 序列，软删行不返回，上限截断）
     */
    @Tool(description = "按文档 ID 读取知识库文档的完整内容（元信息 + 按序正文片段）。"
        + "文档 ID 可经 searchKnowledge 结果或对话溯源获得")
    public DocumentText getDocument(
            @ToolParam(description = "文档 ID（kb_document 主键）") String documentId,
            ToolContext toolContext) {
        RetrievalContext ctx = requireContext(toolContext);
        // 租户 fail-closed：不存在与跨租户一律 KB_DOC_NOT_FOUND（对齐 MCP get_document 语义）
        KbDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !ctx.getTenantId().equals(doc.getTenantId())) {
            throw new BusinessException("KB_DOC_NOT_FOUND", "文档不存在: " + documentId);
        }

        List<ChunkText> chunks = chunkRepository.findByDocIdOrderByChunkIndex(documentId).stream()
            .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
            .limit(documentMaxChunks)
            .map(c -> new ChunkText(c.getChunkIndex(), headingPathOf(c.getMetadata()),
                c.getPageNum(), c.getContent()))
            .toList();
        ctx.addToolCall(new RetrievalContext.ToolCall("get:document",
            RetrievalContext.ToolCall.STATUS_EXECUTED, null, "读文档: " + documentId));
        return new DocumentText(doc.getId(), doc.getName(), doc.getType(),
            doc.getPageCount(), doc.getChunkCount(), chunks);
    }

    // ── 内部方法 ──

    /**
     * 隔离检索上下文（热修五 trace 隔离）：仅拷贝租户身份喂检索管线——多路命中
     * 与改写文本写入隔离实例随请求丢弃，主请求 trace / rewritten_query 不被子代理
     * 检索污染（身份 fail-closed 语义经隔离实例完整保留）。
     */
    private static RetrievalContext isolatedContext(RetrievalContext source) {
        RetrievalContext isolated = new RetrievalContext();
        isolated.setTenantId(source.getTenantId());
        isolated.setUserId(source.getUserId());
        return isolated;
    }

    /** 主请求快照内已执行的检索次数（跨委派与超时弃任务同计数，CopyOnWrite 容器线程安全） */
    private static int countExecutedSearches(RetrievalContext ctx) {
        return (int) ctx.getToolCalls().stream()
            .filter(tc -> "search:knowledge".equals(tc.toolName()))
            .count();
    }

    /** 正文载荷截断（超长加截断标记；高频检索 = 摘要级载荷，全文经 getDocument） */
    private String truncate(String content) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "…（已截断，全文经 getDocument）";
    }

    /** query 摘要（检索审计记录用，60 字符） */
    private static String abbreviate(String query) {
        if (query == null) {
            return "";
        }
        return query.length() <= 60 ? query : query.substring(0, 60) + "…";
    }

    /** 身份提取（TaskTool 下传链）：缺 ctx 或缺租户 fail-closed 拒绝 */
    private static RetrievalContext requireContext(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(ToolContextKeys.RETRIEVAL_CONTEXT);
        RetrievalContext ctx = value instanceof RetrievalContext rc ? rc : null;
        if (ctx == null || ctx.getTenantId() == null || ctx.getTenantId().isBlank()) {
            throw new BusinessException("IDENTITY_INCOMPLETE", "身份不完整：子代理工具无法执行租户过滤");
        }
        return ctx;
    }

    /** metadata JSONB 解析 heading_path（容错：损坏回落 null，同 McpKnowledgeTools 形态） */
    private String headingPathOf(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            Map meta = jsonMapper.readValue(metadataJson, Map.class);
            Object value = meta.get("heading_path");
            return value != null ? String.valueOf(value) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    /** 检索命中视图（子代理 LLM 消费面：字段精简，rank 为重排后序，content 为截断后摘要） */
    public record SearchHit(String chunkId, String fileName, String headingPath,
                            Integer pageNum, String content, int rank) {
    }

    /** 检索结果外层视图（热修五）：hits 命中列表 + note 预算提示（含停止指令时子代理必须服从） */
    public record SearchOutcome(List<SearchHit> hits, String note) {
    }

    /** 文档全文视图（chunk 上限 rag.orchestrator.knowledge.max-chunks 截断） */
    public record DocumentText(String documentId, String name, String type,
                               Integer pageCount, Integer chunkCount, List<ChunkText> chunks) {
    }

    /** 文档正文片段视图（chunkIndex 保序） */
    public record ChunkText(Integer chunkIndex, String headingPath, Integer pageNum, String content) {
    }
}
