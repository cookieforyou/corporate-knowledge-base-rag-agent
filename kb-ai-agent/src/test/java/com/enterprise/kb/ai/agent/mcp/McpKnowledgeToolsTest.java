package com.enterprise.kb.ai.agent.mcp;

import com.enterprise.kb.ai.agent.mcp.McpKnowledgeTools.ChunkTextView;
import com.enterprise.kb.ai.agent.mcp.McpKnowledgeTools.DocumentView;
import com.enterprise.kb.ai.agent.mcp.McpKnowledgeTools.SearchHitView;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.ai.service.RagChatService;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * McpKnowledgeTools 单测（簇⑤ 4.10）——三件套编排：检索投影、文档租户守卫
 * 与软删过滤/截断、ask 链委派与会话隔离。身份守卫语义见 McpIdentityGuardTest。
 */
class McpKnowledgeToolsTest {

    private static final String TENANT = "t-1";

    private HybridDocumentRetriever hybridRetriever;
    private RerankDocumentPostProcessor rerankPostProcessor;
    private QueryTransformer rewriteQueryTransformer;
    private RagChatService ragChatService;
    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private McpIdentityGuard identityGuard;
    private AiBusinessMetrics metrics;
    private McpKnowledgeTools tools;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridDocumentRetriever.class);
        rerankPostProcessor = mock(RerankDocumentPostProcessor.class);
        rewriteQueryTransformer = mock(QueryTransformer.class);
        ragChatService = mock(RagChatService.class);
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        identityGuard = mock(McpIdentityGuard.class);
        metrics = mock(AiBusinessMetrics.class);

        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId(TENANT);
        ctx.setUserId("u-1");
        when(identityGuard.requireIdentity()).thenReturn(ctx);

        tools = new McpKnowledgeTools(hybridRetriever, rerankPostProcessor,
            rewriteQueryTransformer, ragChatService, documentRepository,
            chunkRepository, identityGuard, metrics, new JsonMapper(), 2);
    }

    // ── search ──

    @Test
    void searchMapsFinalsToHitsWithMetadataAndRanks() {
        when(rewriteQueryTransformer.apply(any(Query.class))).thenReturn(new Query("改写后"));
        when(hybridRetriever.retrieve(any(Query.class))).thenReturn(List.of());
        Document doc = new Document("c-1", "证据正文", Map.of(
            "file_name", "产品手册.pdf", "heading_path", "第三章 > 质保",
            "page_num", 12, "chunk_type", "TEXT", "rerank_score", 0.93));
        when(rerankPostProcessor.process(any(Query.class), any())).thenReturn(List.of(doc));

        List<SearchHitView> hits = tools.search("质保期多久");

        assertThat(hits).hasSize(1);
        SearchHitView hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("c-1");
        assertThat(hit.fileName()).isEqualTo("产品手册.pdf");
        assertThat(hit.headingPath()).isEqualTo("第三章 > 质保");
        assertThat(hit.pageNum()).isEqualTo(12);
        assertThat(hit.content()).isEqualTo("证据正文");
        assertThat(hit.rerankScore()).isEqualTo(0.93);
        assertThat(hit.finalRank()).isEqualTo(1);
        verify(metrics).recordMcpToolCall("search");
    }

    @Test
    void searchBlankQueryRejected() {
        assertThatThrownBy(() -> tools.search("  "))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_QUERY_EMPTY");
    }

    // ── get_document ──

    @Test
    void getDocumentCrossTenantAndMissingHidden() {
        KbDocument crossTenant = doc("d-1");
        crossTenant.setTenantId("t-other");
        when(documentRepository.findById("d-1")).thenReturn(Optional.of(crossTenant));
        when(documentRepository.findById("d-none")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tools.getDocument("d-1"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_DOC_NOT_FOUND");
        assertThatThrownBy(() -> tools.getDocument("d-none"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_DOC_NOT_FOUND");
    }

    @Test
    void getDocumentFiltersSoftDeletedAndTruncatesToMaxChunks() {
        when(documentRepository.findById("d-1")).thenReturn(Optional.of(doc("d-1")));
        when(chunkRepository.findByDocIdOrderByChunkIndex("d-1"))
            .thenReturn(List.of(
                chunk("c-1", 0, false, "{\"heading_path\":\"第一章\"}"),
                chunk("c-2", 1, true, "{}"),          // 软删行不返回
                chunk("c-3", 2, false, "{}"),
                chunk("c-4", 3, false, "corrupt{"))); // maxChunks=2 截断不可达

        DocumentView view = tools.getDocument("d-1");

        assertThat(view.documentId()).isEqualTo("d-1");
        assertThat(view.name()).isEqualTo("产品手册.pdf");
        // 软删行过滤后存活 3 条，maxChunks=2 截断 → c-1/c-3（c-4 不可达）
        assertThat(view.chunks()).extracting(ChunkTextView::chunkIndex).containsExactly(0, 2);
        assertThat(view.chunks().get(0).headingPath()).isEqualTo("第一章");
        verify(metrics).recordMcpToolCall("get_document");
    }

    // ── ask ──

    @Test
    void askDelegatesToRagChainWithIsolatedSession() {
        when(ragChatService.chatRag(anyString(), anyString(), any(RetrievalContext.class)))
            .thenReturn("答案 [ref-1]");

        String answer = tools.ask("质保期多久");

        assertThat(answer).isEqualTo("答案 [ref-1]");
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        verify(ragChatService).chatRag(eq("质保期多久"), sessionId.capture(), any(RetrievalContext.class));
        assertThat(sessionId.getValue()).startsWith("mcp-");
        verify(metrics).recordMcpToolCall("ask");
    }

    @Test
    void askBlankQuestionRejected() {
        assertThatThrownBy(() -> tools.ask(null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("MCP_QUERY_EMPTY");
    }

    // ── helpers ──

    private static KbDocument doc(String id) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setTenantId(TENANT);
        doc.setName("产品手册.pdf");
        doc.setType("PDF");
        doc.setStatus(DocumentStatus.SUCCESS);
        return doc;
    }

    private static KbChunk chunk(String id, int index, boolean deleted, String metadata) {
        KbChunk chunk = new KbChunk();
        chunk.setId(id);
        chunk.setChunkIndex(index);
        chunk.setContent("正文-" + index);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setIsDeleted(deleted);
        chunk.setMetadata(metadata);
        return chunk;
    }
}
