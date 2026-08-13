package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgressRedisWriter;
import com.enterprise.kb.etl.service.ChunkCleanupService;
import com.enterprise.kb.etl.service.DocumentEtlService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DocumentService 单测（簇⑥ C1 补零测盲区）：
 * 删除级联委派共享组件 + 重入库双端点（reparse/replace）状态守卫/租户守卫/失败语义。
 */
class DocumentServiceTest {

    private static final String DOC_ID = "doc-1";
    private static final String TENANT = "t-1";

    private MinioClient minioClient;
    private KbDocumentRepository documentRepository;
    private KbChunkRepository chunkRepository;
    private DocumentEtlService etlService;
    private EtlProgressRedisWriter progressWriter;
    private ChunkCleanupService chunkCleanupService;
    private AiBusinessMetrics metrics;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        documentRepository = mock(KbDocumentRepository.class);
        chunkRepository = mock(KbChunkRepository.class);
        etlService = mock(DocumentEtlService.class);
        progressWriter = mock(EtlProgressRedisWriter.class);
        chunkCleanupService = mock(ChunkCleanupService.class);
        metrics = mock(AiBusinessMetrics.class);
        service = new DocumentService(minioClient, documentRepository, chunkRepository,
            etlService, progressWriter, chunkCleanupService, metrics);
        // @Value 字段测试注入：MinIO args builder 在 build 时即校验 bucket 非空
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        when(progressWriter.andThen(any())).thenReturn(p -> { });
    }

    private KbDocument doc(String tenant, DocumentStatus status) {
        KbDocument doc = new KbDocument();
        doc.setId(DOC_ID);
        doc.setTenantId(tenant);
        doc.setName("手册.pdf");
        doc.setOriginalName("手册.pdf");
        doc.setType("PDF");
        doc.setOssPath(DOC_ID + "/手册.pdf");
        doc.setStatus(status);
        doc.setVersion(1);
        return doc;
    }

    private KbChunk chunk(String id) {
        KbChunk c = new KbChunk();
        c.setId(id);
        return c;
    }

    // ── 删除级联（委派共享组件，簇⑥ C1）──

    @Test
    void deleteDelegatesCascadeToSharedCleanupComponent() {
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID))
            .thenReturn(List.of(chunk("c-1"), chunk("c-2")));

        service.delete(DOC_ID, TENANT);

        // 三库级联经共享组件（esByDocId=true 文档级扫尾）+ MinIO + 文档行
        verify(chunkCleanupService).physicalDelete(DOC_ID, List.of("c-1", "c-2"), true);
        verify(documentRepository).delete(document);
    }

    @Test
    void deleteIsIdempotentWhenMissing() {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.empty());
        service.delete(DOC_ID, TENANT);
        verifyNoInteractions(chunkCleanupService);
        verify(documentRepository, never()).delete(any());
    }

    @Test
    void deleteCrossTenantRejected() {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc("t-other", DocumentStatus.SUCCESS)));
        assertThatThrownBy(() -> service.delete(DOC_ID, TENANT))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权");
        verifyNoInteractions(chunkCleanupService);
    }

    /**
     * 处理期（UPLOADING/PARSING/REINDEXING）禁删（簇⑥ C1 收尾）——防级联清理与
     * 在途 ETL 竞态、防重入库窗口误删；守卫在租户校验之后（不跨租户泄露状态）。
     */
    @ParameterizedTest
    @EnumSource(names = {"UPLOADING", "PARSING", "REINDEXING"})
    void deleteBlockedWhileProcessing(DocumentStatus status) {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc(TENANT, status)));

        assertThatThrownBy(() -> service.delete(DOC_ID, TENANT))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("DOC_NOT_READY");
        verifyNoInteractions(chunkCleanupService, minioClient);
        verify(documentRepository, never()).delete(any());
    }

    /** FAILED 态放行——失败文档删除是正当清理路径（守卫不过度拦截） */
    @Test
    void deleteAllowedWhenFailed() {
        KbDocument document = doc(TENANT, DocumentStatus.FAILED);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID)).thenReturn(List.of());

        service.delete(DOC_ID, TENANT);

        verify(chunkCleanupService).physicalDelete(DOC_ID, List.of(), true);
        verify(documentRepository).delete(document);
    }

    // ── reparse（重解析）──

    @Test
    void reparseAcquiresAndTriggersEtlWithStoredRoute() {
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        document.setParseRoute("DEEP");
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(eq(DOC_ID), eq(DocumentStatus.REINDEXING), anyList()))
            .thenReturn(1);

        service.reparse(DOC_ID, TENANT, null);

        verify(documentRepository).acquireForReindex(DOC_ID, DocumentStatus.REINDEXING,
            List.of(DocumentStatus.SUCCESS, DocumentStatus.FAILED));
        // 占用成功后内存态同步（@Modifying 只更新 DB，不同步则后续 save 回写旧状态）
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.REINDEXING);
        verify(metrics).recordReindexStarted();
        verify(etlService).process(eq(DOC_ID), any(), eq(ParseRoute.DEEP));  // 复现原始路由
    }

    @Test
    void reparseExplicitRouteOverridesStored() {
        KbDocument document = doc(TENANT, DocumentStatus.FAILED);
        document.setParseRoute("DEEP");
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);

        service.reparse(DOC_ID, TENANT, "OCR");

        verify(etlService).process(eq(DOC_ID), any(), eq(ParseRoute.OCR));
    }

    @Test
    void reparseBlockedWhenNotAcquirable() {
        KbDocument document = doc(TENANT, DocumentStatus.PARSING);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(0);

        assertThatThrownBy(() -> service.reparse(DOC_ID, TENANT, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("DOC_NOT_READY");
        verify(etlService, never()).process(anyString(), any(), any());
        verify(metrics, never()).recordReindexStarted();
    }

    @Test
    void reparseCrossTenantRejected() {
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc("t-other", DocumentStatus.SUCCESS)));
        assertThatThrownBy(() -> service.reparse(DOC_ID, TENANT, null))
            .isInstanceOf(BusinessException.class);
        verify(documentRepository, never()).acquireForReindex(anyString(), any(), anyList());
    }

    // ── replace（替换原件）──

    @Test
    void replaceOverwritesOriginalAndTriggersEtl() throws Exception {
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        document.setParseRoute("DEEP");
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);
        MockMultipartFile file = new MockMultipartFile(
            "file", "手册-v2.pdf", "application/pdf", "new".getBytes());

        service.replace(DOC_ID, TENANT, file, null);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        assertThat(document.getName()).isEqualTo("手册-v2.pdf");
        assertThat(document.getType()).isEqualTo("PDF");
        assertThat(document.getSize()).isEqualTo(3L);
        // 回归（2026-08-13 E2E 缺陷）：元数据保存必须保留 REINDEXING 占用态——
        // 回写陈旧 SUCCESS 会使 ETL 误判首次入库（version 不递增、PARSING 顶替展示态）
        ArgumentCaptor<KbDocument> captor = ArgumentCaptor.forClass(KbDocument.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DocumentStatus.REINDEXING);
        // 新文件不复用旧版本路由——显式参数缺省 → 自动决策
        verify(etlService).process(eq(DOC_ID), any(), eq(null));
        verify(metrics).recordReindexStarted();
    }

    @Test
    void replaceMinioFailureMarksFailedAndThrows() throws Exception {
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);
        doThrow(new RuntimeException("MinIO 不可达")).when(minioClient).putObject(any(PutObjectArgs.class));
        MockMultipartFile file = new MockMultipartFile(
            "file", "手册-v2.pdf", "application/pdf", "new".getBytes());

        assertThatThrownBy(() -> service.replace(DOC_ID, TENANT, file, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("UPLOAD_FAILED");

        // 占用已生效：落 FAILED 态（FAILED 可重试 reparse——原件未被破坏）
        ArgumentCaptor<KbDocument> captor = ArgumentCaptor.forClass(KbDocument.class);
        verify(documentRepository, atLeastOnce()).save(captor.capture());
        KbDocument saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(saved.getErrorMessage()).contains("MinIO 不可达");
        verify(etlService, never()).process(anyString(), any(), any());
    }

    @Test
    void replaceBlockedWhenNotAcquirableBeforeMinioWrite() {
        KbDocument document = doc(TENANT, DocumentStatus.REINDEXING);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(0);
        MockMultipartFile file = new MockMultipartFile(
            "file", "手册-v2.pdf", "application/pdf", "new".getBytes());

        assertThatThrownBy(() -> service.replace(DOC_ID, TENANT, file, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("DOC_NOT_READY");
        verifyNoInteractions(minioClient);   // 快速失败：无谓 MinIO 写入不发生
    }

    @Test
    void replaceEmptyFileRejected() {
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        MockMultipartFile empty = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> service.replace(DOC_ID, TENANT, empty, null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("FILE_EMPTY");
    }
}
