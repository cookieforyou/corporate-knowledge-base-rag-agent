package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 知识库三件套工具（Phase 4 簇⑤ 4.10）——企业知识底座对外 MCP 暴露面。
 *
 * <p><b>注册形态</b>：{@code @McpTool} 方法经 server starter 注解扫描器
 * （McpServerAnnotationScannerAutoConfiguration，默认开）自动收编为 MCP 工具，
 * 零手工 specification 装配；传输/端点装配落 kb-api（spring-ai-starter-mcp-server-webmvc，
 * Streamable HTTP /mcp）。工具粒度对齐业界共识（Vectara/Notion 形态）：
 * {@code search}（混合检索）+ {@code get_document}（文档全文）+ {@code ask}（RAG 问答）。
 *
 * <p><b>链复用</b>：search 直调检索链（改写 → 双路召回 → RRF → 重排，同
 * RetrievalDebugController 形态，租户过滤经 RetrievalContext 参数链）；
 * ask 经 ragAgentChatClient 全链——意图路由/护栏/配额/审计/多模型路由**自动复用**
 * （注入载荷 → PROMPT_INJECTION 拒答经 MCP 错误帧回传，DoD 护栏验证项）；
 * get_document 纯 PG 读（租户 fail-closed）。三工具均经 {@link McpIdentityGuard}
 * 请求线程捕获 JWT → 参数链（无 ThreadLocal 传递）。
 *
 * <p><b>审计形态</b>：ask 落 kb_audit_log 全链路快照（AuditTraceAdvisor 链上既有）；
 * search/get_document 非对话调用，审计经 rag.mcp.* 指标面（零标签纪律）。
 *
 * <p><b>scope 治理</b>：调用级强制（McpIdentityGuard）；tools/list 注册面为静态
 * 全集（注解扫描器形态），部署级可见性经网关/客户端配置治理，留档不内建。
 */
@Slf4j
@Component
public class McpKnowledgeTools {

    private final HybridDocumentRetriever hybridRetriever;
    private final RerankDocumentPostProcessor rerankPostProcessor;
    private final QueryTransformer rewriteQueryTransformer;
    private final RagChatService ragChatService;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final McpIdentityGuard identityGuard;
    private final AiBusinessMetrics metrics;
    private final JsonMapper jsonMapper;
    private final int documentMaxChunks;

    public McpKnowledgeTools(HybridDocumentRetriever hybridRetriever,
                             RerankDocumentPostProcessor rerankPostProcessor,
                             @Qualifier("rewriteQueryTransformer") QueryTransformer rewriteQueryTransformer,
                             RagChatService ragChatService,
                             KbDocumentRepository documentRepository,
                             KbChunkRepository chunkRepository,
                             McpIdentityGuard identityGuard,
                             AiBusinessMetrics metrics,
                             JsonMapper jsonMapper,
                             @Value("${rag.mcp.get-document.max-chunks:50}") int documentMaxChunks) {
        this.hybridRetriever = hybridRetriever;
        this.rerankPostProcessor = rerankPostProcessor;
        this.rewriteQueryTransformer = rewriteQueryTransformer;
        this.ragChatService = ragChatService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.identityGuard = identityGuard;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.documentMaxChunks = Math.max(1, documentMaxChunks);
    }

    /** 混合检索：改写 → 双路召回 → RRF 融合 → 重排，返回 Top-K 候选（不经 LLM） */
    @McpTool(name = "search",
        description = "在企业知识库中执行混合检索（向量 + BM25 + 重排序），返回与查询最相关的文档片段列表（含文件名/页码/标题路径/正文）。适用于查找事实、定位证据文档。",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public List<SearchHitView> search(
        @McpArg(name = "query", description = "自然语言检索问题", required = true) String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException("MCP_QUERY_EMPTY", "检索问题不可为空");
        }
        RetrievalContext ctx = identityGuard.requireIdentity();
        metrics.recordMcpToolCall("search");

        Query rewritten = rewriteQueryTransformer.apply(new Query(query));
        Map<String, Object> queryContext = Map.of(RetrievalContext.CONTEXT_KEY, ctx);
        List<Document> fused = hybridRetriever.retrieve(
            Query.builder().text(rewritten.text()).context(queryContext).build());
        List<Document> finals = rerankPostProcessor.process(
            Query.builder().text(rewritten.text()).context(queryContext).build(), fused);

        List<SearchHitView> hits = new ArrayList<>(finals.size());
        int rank = 0;
        for (Document doc : finals) {
            rank++;
            Map<String, Object> meta = doc.getMetadata();
            hits.add(new SearchHitView(doc.getId(),
                asString(meta.get("file_name")),
                asString(meta.get("heading_path")),
                meta.get("page_num") instanceof Number n ? n.intValue() : null,
                asString(meta.get("chunk_type")),
                doc.getText(),
                meta.get("rerank_score") instanceof Number s ? s.doubleValue() : null,
                rank));
        }
        return hits;
    }

    /** 文档全文读取：文档元信息 + 存活 chunk 序列（软删行不返回，上限截断） */
    @McpTool(name = "get_document",
        description = "按文档 ID 读取知识库文档的完整内容（元信息 + 按序正文片段）。文档 ID 可经 search 工具结果的 chunkId 前缀或对话溯源获得。",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public DocumentView getDocument(
        @McpArg(name = "documentId", description = "文档 ID（kb_document 主键）", required = true) String documentId) {
        RetrievalContext ctx = identityGuard.requireIdentity();
        metrics.recordMcpToolCall("get_document");

        // 租户 fail-closed：不存在与跨租户一律 MCP_DOC_NOT_FOUND（不泄露存在性）
        KbDocument doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || !ctx.getTenantId().equals(doc.getTenantId())) {
            throw new BusinessException("MCP_DOC_NOT_FOUND", "文档不存在: " + documentId);
        }

        List<ChunkTextView> chunks = chunkRepository.findByDocIdOrderByChunkIndex(documentId).stream()
            .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
            .limit(documentMaxChunks)
            .map(c -> new ChunkTextView(c.getChunkIndex(), headingPathOf(c.getMetadata()),
                c.getPageNum(), c.getContent()))
            .toList();
        return new DocumentView(doc.getId(), doc.getName(), doc.getType(),
            doc.getStatus() != null ? doc.getStatus().name() : null,
            doc.getParseRoute(), doc.getPageCount(), doc.getChunkCount(), chunks);
    }

    /** RAG 问答：经 ragAgentChatClient 全链（意图路由/护栏/配额/审计/多模型路由自动复用） */
    @McpTool(name = "ask",
        description = "向企业知识库提问并获得带引用编号（[ref-N]）的 RAG 回答。适用于需要综合多个文档证据回答的问题；事实定位请优先用 search。",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String ask(
        @McpArg(name = "question", description = "面向知识库的自然语言问题", required = true) String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("MCP_QUERY_EMPTY", "问题不可为空");
        }
        RetrievalContext ctx = identityGuard.requireIdentity();
        metrics.recordMcpToolCall("ask");
        // MCP 调用无会话语义：每次调用独立会话 ID（记忆 Advisor 硬断言需非空）；
        // mcp- 前缀保留来源标记 + 去横线 UUID 钉死 36 字符——kb_audit_log.session_id
        // VARCHAR(36)，带横线 UUID 前缀形态 40 字符致审计落库失败（E2E 实证）
        String sessionId = "mcp-" + UUID.randomUUID().toString().replace("-", "");
        return ragChatService.chatRag(question, sessionId, ctx);
    }

    // ── 内部方法 ──

    /** metadata JSONB 解析 heading_path（容错：损坏回落 null，同 ChunkOpsService 形态） */
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

    /** search 命中视图（LLM 消费面：字段精简，得分为重排后序） */
    public record SearchHitView(String chunkId, String fileName, String headingPath,
                                Integer pageNum, String chunkType, String content,
                                Double rerankScore, int finalRank) {
    }

    /** get_document 文档视图（chunk 上限 rag.mcp.get-document.max-chunks 截断） */
    public record DocumentView(String documentId, String name, String type, String status,
                               String parseRoute, Integer pageCount, Integer chunkCount,
                               List<ChunkTextView> chunks) {
    }

    /** 文档正文片段视图（chunkIndex 保序，headingPath 经 metadata 回填） */
    public record ChunkTextView(Integer chunkIndex, String headingPath, Integer pageNum,
                                String content) {
    }
}
