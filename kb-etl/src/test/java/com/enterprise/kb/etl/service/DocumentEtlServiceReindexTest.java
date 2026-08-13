package com.enterprise.kb.etl.service;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgress;
import com.enterprise.kb.etl.pipeline.EtlStage;
import com.enterprise.kb.etl.reader.SmartParsingRouter;
import com.enterprise.kb.etl.transformer.ContextualEnrichmentTransformer;
import com.enterprise.kb.etl.transformer.HtmlProtectingSplitter;
import com.enterprise.kb.etl.transformer.SanitizingTransformer;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 蓝绿重入库管线单测（簇⑥ C1）：
 * 全量写入 → diff 清理（旧有新无）→ REINDEXING 入口版本号 +1；
 * 首次入库旧集为空零清理；失败态旧数据保留不触发清理。
 */
class DocumentEtlServiceReindexTest {

    private static final String DOC_ID = "doc-1";
    private static final String DOC_NAME = "手册.md";

    private MinioClient minioClient;
    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private VectorStore vectorStore;
    private EsIndexWriter esIndexWriter;
    private SmartParsingRouter parsingRouter;
    private HtmlProtectingSplitter protectingSplitter;
    private SanitizingTransformer sanitizingTransformer;
    private ChunkCleanupService chunkCleanupService;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ContextualEnrichmentTransformer> contextualProvider = mock(ObjectProvider.class);

    private DocumentEtlService etlService;
    private final List<EtlStage> stages = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        minioClient = mock(MinioClient.class);
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        vectorStore = mock(VectorStore.class);
        esIndexWriter = mock(EsIndexWriter.class);
        parsingRouter = mock(SmartParsingRouter.class);
        protectingSplitter = mock(HtmlProtectingSplitter.class);
        sanitizingTransformer = mock(SanitizingTransformer.class);
        chunkCleanupService = mock(ChunkCleanupService.class);

        etlService = new DocumentEtlService(minioClient, documentRepository, chunkRepository,
            vectorStore, esIndexWriter, parsingRouter, protectingSplitter, sanitizingTransformer,
            chunkCleanupService, mock(JsonMapper.class), contextualProvider);
        ReflectionTestUtils.setField(etlService, "bucket", "test-bucket");
        ReflectionTestUtils.setField(etlService, "embedBatchSize", 10);

        // MinIO 原件读取桩
        GetObjectResponse objectResponse = mock(GetObjectResponse.class);
        when(objectResponse.transferTo(any(OutputStream.class))).thenReturn(0L);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(objectResponse);

        when(contextualProvider.getIfAvailable()).thenReturn(null);
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private KbDocument doc(DocumentStatus status, Integer version) {
        KbDocument doc = new KbDocument();
        doc.setId(DOC_ID);
        doc.setTenantId("t-1");
        doc.setName(DOC_NAME);
        doc.setOssPath(DOC_ID + "/" + DOC_NAME);
        doc.setStatus(status);
        doc.setVersion(version);
        return doc;
    }

    private void stubPipeline(List<String> chunkTexts) {
        when(parsingRouter.read(any(byte[].class), anyString(), any()))
            .thenReturn(new SmartParsingRouter.ParsingOutcome(
                List.of(new Document("原始页内容")), ParseRoute.NATIVE));
        List<Document> chunks = chunkTexts.stream().map(Document::new).toList();
        when(protectingSplitter.apply(anyList())).thenReturn(chunks);
        when(sanitizingTransformer.apply(anyList())).thenReturn(chunks);
    }

    private KbChunk oldChunk(String id) {
        KbChunk c = new KbChunk();
        c.setId(id);
        c.setDocId(DOC_ID);
        return c;
    }

    @Test
    void reindexDiffCleansStaleChunksAndBumpsVersion() {
        String keepId = DocumentEtlService.deterministicChunkId(DOC_NAME, 0, "保持不变的内容");
        String staleId = "stale-chunk-id";
        KbDocument document = doc(DocumentStatus.REINDEXING, 3);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID))
            .thenReturn(List.of(oldChunk(keepId), oldChunk(staleId)));
        stubPipeline(List.of("保持不变的内容", "新增的内容"));

        etlService.process(DOC_ID, p -> stages.add(p.getStage()), null);

        // 蓝绿 diff：仅清理「旧有新无」，同 ID 覆写者（keepId）不在 diff 内
        verify(chunkCleanupService).physicalDelete(DOC_ID, List.of(staleId), false);
        // 终态：SUCCESS + version 3→4 + 清空遗留 errorMessage
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.SUCCESS);
        assertThat(document.getVersion()).isEqualTo(4);
        assertThat(document.getErrorMessage()).isNull();
        assertThat(document.getChunkCount()).isEqualTo(2);
        assertThat(stages).contains(EtlStage.CLEANUP, EtlStage.COMPLETED);
    }

    @Test
    void firstIngestHasEmptyDiffAndKeepsVersion() {
        KbDocument document = doc(DocumentStatus.UPLOADING, 1);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID)).thenReturn(List.of());
        stubPipeline(List.of("第一段", "第二段"));

        etlService.process(DOC_ID, p -> stages.add(p.getStage()), null);

        verify(chunkCleanupService, never()).physicalDelete(anyString(), anyList(), anyBoolean());
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.SUCCESS);
        assertThat(document.getVersion()).isEqualTo(1);   // 首次入库不增版本
        assertThat(document.getParseRoute()).isEqualTo("NATIVE");
        assertThat(stages).contains(EtlStage.CLEANUP);    // CLEANUP 阶段常设（空操作）
    }

    @Test
    void reindexFailureKeepsOldDataAndSkipsCleanup() {
        KbDocument document = doc(DocumentStatus.REINDEXING, 2);
        document.setErrorMessage("上轮遗留错误");
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID))
            .thenReturn(List.of(oldChunk("old-1")));
        when(parsingRouter.read(any(byte[].class), anyString(), any()))
            .thenThrow(new RuntimeException("解析服务爆炸"));

        assertThatThrownBy(() -> etlService.process(DOC_ID, p -> stages.add(p.getStage()), null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("解析服务爆炸");

        verify(chunkCleanupService, never()).physicalDelete(anyString(), anyList(), anyBoolean());
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getVersion()).isEqualTo(2);   // 失败不增版本
        assertThat(stages).contains(EtlStage.FAILED);
    }

    @Test
    void staleChunkIdsDiffPreservesOrderAndOverlaps() {
        assertThat(DocumentEtlService.staleChunkIds(
            List.of("a", "b", "c"), List.of("b", "d")))
            .containsExactly("a", "c");
        assertThat(DocumentEtlService.staleChunkIds(List.of(), List.of("x"))).isEmpty();
        assertThat(DocumentEtlService.staleChunkIds(List.of("x"), List.of("x"))).isEmpty();
    }
}
