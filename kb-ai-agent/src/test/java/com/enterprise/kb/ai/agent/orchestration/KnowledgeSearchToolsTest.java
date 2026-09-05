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
            documentRepository, chunkRepository, JsonMapper.builder().build(), 2);
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

        List<KnowledgeSearchTools.SearchHit> hits =
            tools.searchKnowledge("差旅报销标准", toolContext("tenant-a"));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).fileName()).isEqualTo("差旅制度.md");
        assertThat(hits.get(0).headingPath()).isEqualTo("报销 > 标准");
        assertThat(hits.get(0).pageNum()).isEqualTo(3);
        assertThat(hits.get(0).content()).isEqualTo("差旅报销标准正文");
        assertThat(hits.get(0).rank()).isEqualTo(1);
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
