package com.enterprise.kb.ai.agent.orchestration;

import com.enterprise.kb.ai.agent.tool.ToolContextKeys;
import com.enterprise.kb.ai.retriever.HybridDocumentRetriever;
import com.enterprise.kb.ai.retriever.RerankDocumentPostProcessor;
import com.enterprise.kb.ai.retriever.RetrievalContext;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 知识检索子代理工具测试（簇⑤ 批2）——身份 fail-closed（TaskTool 下传链）/
 * 检索管线透传与投影 / 跨租户隐藏 / 软删过滤与上限截断
 */
class KnowledgeSearchToolsTest {

    private HybridDocumentRetriever hybridRetriever;
    private RerankDocumentPostProcessor rerankPostProcessor;
    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private KnowledgeSearchTools tools;

    @BeforeEach
    void setUp() {
        hybridRetriever = mock(HybridDocumentRetriever.class);
        rerankPostProcessor = mock(RerankDocumentPostProcessor.class);
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        QueryTransformer transformer = mock(QueryTransformer.class);
        when(transformer.apply(any(Query.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rerankPostProcessor.process(any(Query.class), any()))
            .thenAnswer(inv -> inv.getArgument(1));
        tools = new KnowledgeSearchTools(hybridRetriever, rerankPostProcessor, transformer,
            documentRepository, chunkRepository, JsonMapper.builder().build(), 2, 400, 6);
    }

    private static ToolContext toolContext(String tenantId) {
        Map<String, Object> map = new HashMap<>();
        if (tenantId != null) {
            RetrievalContext ctx = new RetrievalContext();
            ctx.setTenantId(tenantId);
            map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);
        }
        return new ToolContext(map);
    }

    @Test
    void searchKnowledgeProjectsRetrieverHits() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("file_name", "差旅制度.md");
        meta.put("heading_path", "报销 > 标准");
        meta.put("page_num", 3);
        Document doc = new Document("差旅报销标准正文", meta);
        when(hybridRetriever.retrieve(any(Query.class))).thenReturn(List.of(doc));

        KnowledgeSearchTools.SearchOutcome outcome =
            tools.searchKnowledge("差旅报销标准", toolContext("tenant-a"));

        assertThat(outcome.hits()).hasSize(1);
        assertThat(outcome.hits().get(0).fileName()).isEqualTo("差旅制度.md");
        assertThat(outcome.hits().get(0).headingPath()).isEqualTo("报销 > 标准");
        assertThat(outcome.hits().get(0).pageNum()).isEqualTo(3);
        assertThat(outcome.hits().get(0).content()).isEqualTo("差旅报销标准正文");
        assertThat(outcome.hits().get(0).rank()).isEqualTo(1);
        assertThat(outcome.note()).contains("剩余");
    }

    @Test
    void searchOutcomeCarriesBudgetNoteAndAuditRecord() {
        // 热修五：命中正常返回 + 主 ctx 记 search:knowledge 审计条目（计数载体）
        when(hybridRetriever.retrieve(any(Query.class))).thenReturn(List.of());
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        Map<String, Object> map = new HashMap<>();
        map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);

        KnowledgeSearchTools.SearchOutcome outcome =
            tools.searchKnowledge("数据分级分类要点", new ToolContext(map));

        assertThat(outcome.note()).contains("5/6");
        assertThat(ctx.getToolCalls()).hasSize(1);
        assertThat(ctx.getToolCalls().get(0).toolName()).isEqualTo("search:knowledge");
        assertThat(ctx.getToolCalls().get(0).status()).isEqualTo("EXECUTED");
        assertThat(ctx.getToolCalls().get(0).summary()).contains("数据分级分类要点");
    }

    @Test
    void searchBudgetExhaustedReturnsStopNoteWithoutRetrieval() {
        // 热修五：预算尽 → 空结果 + 停止指令，检索管线零触达（不再消耗资源）
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        for (int i = 0; i < 6; i++) {
            ctx.addToolCall(new RetrievalContext.ToolCall("search:knowledge", "EXECUTED", null, "第 " + (i + 1) + " 次"));
        }
        Map<String, Object> map = new HashMap<>();
        map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);

        KnowledgeSearchTools.SearchOutcome outcome =
            tools.searchKnowledge("再多检索一次", new ToolContext(map));

        assertThat(outcome.hits()).isEmpty();
        assertThat(outcome.note()).contains("上限").contains("立即");
        verifyNoInteractions(hybridRetriever);
        // 拒绝不再计数（仍 6 条）
        assertThat(ctx.getToolCalls()).hasSize(6);
    }

    @Test
    void longContentTruncatedToBudget() {
        // 热修五：正文载荷截断（高频检索 = 摘要级载荷）
        String longText = "长".repeat(1000);
        when(hybridRetriever.retrieve(any(Query.class)))
            .thenReturn(List.of(new Document(longText, Map.of())));

        KnowledgeSearchTools.SearchOutcome outcome =
            tools.searchKnowledge("问题", toolContext("tenant-a"));

        assertThat(outcome.hits().get(0).content()).hasSizeLessThan(500).contains("已截断");
    }

    @Test
    void searchTraceIsolatedFromMainRequestContext() {
        // 热修五：检索管线喂隔离 ctx——主请求 trace 零污染（审计行不再累积膨胀），
        // 租户身份经隔离实例下传（检索器消费到的 ctx 携带租户）
        when(hybridRetriever.retrieve(any(Query.class))).thenAnswer(inv -> {
            Query q = inv.getArgument(0);
            RetrievalContext consumed = RetrievalContext.from(q);
            assertThat(consumed).isNotNull().extracting(RetrievalContext::getTenantId).isEqualTo("tenant-a");
            return List.of();
        });
        RetrievalContext ctx = new RetrievalContext();
        ctx.setTenantId("tenant-a");
        Map<String, Object> map = new HashMap<>();
        map.put(ToolContextKeys.RETRIEVAL_CONTEXT, ctx);

        tools.searchKnowledge("问题", new ToolContext(map));

        assertThat(ctx.getTraceSummary()).isEmpty();
        assertThat(ctx.getToolCalls()).hasSize(1);
    }

    @Test
    void searchKnowledgeRequiresIdentityFailClosed() {
        assertThatThrownBy(() -> tools.searchKnowledge("问题", toolContext(null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("IDENTITY_INCOMPLETE");
    }

    @Test
    void crossTenantDocumentHidden() {
        KbDocument doc = mock(KbDocument.class);
        when(doc.getTenantId()).thenReturn("tenant-other");
        when(documentRepository.findById("doc-1")).thenReturn(java.util.Optional.of(doc));

        assertThatThrownBy(() -> tools.getDocument("doc-1", toolContext("tenant-a")))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo("KB_DOC_NOT_FOUND");
    }

    @Test
    void softDeletedChunksFilteredAndCapped() {
        KbDocument doc = mock(KbDocument.class);
        when(doc.getTenantId()).thenReturn("tenant-a");
        when(doc.getId()).thenReturn("doc-1");
        when(doc.getName()).thenReturn("制度汇编");
        when(doc.getType()).thenReturn("md");
        when(doc.getPageCount()).thenReturn(10);
        when(doc.getChunkCount()).thenReturn(5);
        when(documentRepository.findById("doc-1")).thenReturn(java.util.Optional.of(doc));
        // 先建 chunk 列表再 when——thenReturn 参数内嵌套 mock+when 会致 UnfinishedStubbing
        List<KbChunk> chunks = List.of(
            chunk(0, false, "{\"heading_path\":\"总则\"}"),
            chunk(1, true, null),
            chunk(2, false, null),
            chunk(3, false, null));
        when(chunkRepository.findByDocIdOrderByChunkIndex("doc-1")).thenReturn(chunks);

        KnowledgeSearchTools.DocumentText text = tools.getDocument("doc-1", toolContext("tenant-a"));

        // 软删 chunk-1 剔除；上限 2 截断（0、2 保留，3 截断）
        assertThat(text.chunks()).hasSize(2);
        assertThat(text.chunks().get(0).chunkIndex()).isEqualTo(0);
        assertThat(text.chunks().get(0).headingPath()).isEqualTo("总则");
        assertThat(text.chunks().get(1).chunkIndex()).isEqualTo(2);
        assertThat(text.chunks().get(1).headingPath()).isNull();
        assertThat(text.name()).isEqualTo("制度汇编");
    }

    private static KbChunk chunk(int index, boolean deleted, String metadata) {
        KbChunk c = mock(KbChunk.class);
        when(c.getChunkIndex()).thenReturn(index);
        when(c.getIsDeleted()).thenReturn(deleted);
        when(c.getMetadata()).thenReturn(metadata);
        when(c.getPageNum()).thenReturn(index + 1);
        when(c.getContent()).thenReturn("正文-" + index);
        return c;
    }
}
