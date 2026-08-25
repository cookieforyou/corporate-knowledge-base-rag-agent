package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.cache.CacheInvalidationPublisher;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgress;
import com.enterprise.kb.etl.pipeline.EtlProgressRedisWriter;
import com.enterprise.kb.etl.pipeline.EtlStage;
import com.enterprise.kb.etl.pipeline.graph.GraphExtractionPublisher;
import com.enterprise.kb.infrastructure.graph.GraphGateway;
import com.enterprise.kb.etl.service.ChunkCleanupService;
import com.enterprise.kb.etl.service.DocumentEtlService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
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
    private ObjectProvider<CacheInvalidationPublisher> cacheInvalidationPublisher;

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
        cacheInvalidationPublisher = publisherProvider(null);
        service = new DocumentService(minioClient, documentRepository, chunkRepository,
            etlService, progressWriter, chunkCleanupService, metrics, cacheInvalidationPublisher,
            graphPublisherProvider(null), emptyGraphGatewayProvider());   // 图谱抽取派发器缺省缺位（簇④，关闭态零变化）
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

    /** 簇④ 批3：文档删除尽力清理图引用（网关故障不阻断删除主流程） */
    @Test
    void deleteCleansGraphReferencesBestEffort() {
        GraphGateway gateway = mock(GraphGateway.class);
        service = new DocumentService(minioClient, documentRepository, chunkRepository,
            etlService, progressWriter, chunkCleanupService, metrics, cacheInvalidationPublisher,
            graphPublisherProvider(null), gatewayProvider(gateway));
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID)).thenReturn(List.of());

        service.delete(DOC_ID, TENANT);

        verify(gateway).removeDocument(TENANT, DOC_ID);
        verify(documentRepository).delete(document);
    }

    /** 簇④ 批3：图清理故障不击穿删除（尽力而为语义） */
    @Test
    void deleteSucceedsEvenWhenGraphCleanupFails() {
        GraphGateway gateway = mock(GraphGateway.class);
        org.mockito.Mockito.doThrow(new RuntimeException("Neo4j 不可达"))
            .when(gateway).removeDocument(anyString(), anyString());
        service = new DocumentService(minioClient, documentRepository, chunkRepository,
            etlService, progressWriter, chunkCleanupService, metrics, cacheInvalidationPublisher,
            graphPublisherProvider(null), gatewayProvider(gateway));
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(chunkRepository.findByDocIdOrderByChunkIndex(DOC_ID)).thenReturn(List.of());

        service.delete(DOC_ID, TENANT);

        verify(documentRepository).delete(document);   // 删除主流程不受图故障影响
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphGateway> gatewayProvider(GraphGateway gateway) {
        ObjectProvider<GraphGateway> provider = mock(ObjectProvider.class);
        if (gateway != null) {
            org.mockito.Mockito.doAnswer(inv -> {
                ((java.util.function.Consumer<GraphGateway>) inv.getArgument(0)).accept(gateway);
                return null;
            }).when(provider).ifAvailable(any());
        }
        return provider;
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

    /**
     * 终态 future 汇聚（簇③ 4.5 重建编排消费点）：ETL 进度回调 COMPLETED/FAILED
     * 终态帧分别完成 future true/false——进度回调透传形态下捕获回调直接驱动。
     */
    @Test
    void reparseFutureCompletesOnEtlTerminalFrames() {
        // andThen 原样透传回调（捕获终态帧处理 lambda 直接驱动）
        when(progressWriter.andThen(any())).thenAnswer(inv -> inv.getArgument(0));
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);

        java.util.concurrent.CompletableFuture<Boolean> outcome = service.reparse(DOC_ID, TENANT, null);
        assertThat(outcome).isNotCompleted();

        ArgumentCaptor<java.util.function.Consumer<EtlProgress>> captor = callbackCaptor();
        verify(etlService).process(eq(DOC_ID), captor.capture(), any());

        captor.getValue().accept(new EtlProgress(DOC_ID, EtlStage.COMPLETED));
        assertThat(outcome).isCompletedWithValue(true);
    }

    /**
     * 语义缓存失效接线（簇③ 5.6 批2）：ETL COMPLETED 终态帧发布按文档失效事件
     * （覆盖 reparse/replace/重建/首次入库同路径）；FAILED 帧不发布。
     */
    @Test
    void reparseCompletedFramePublishesCacheInvalidation() {
        CacheInvalidationPublisher publisher = mock(CacheInvalidationPublisher.class);
        service = new DocumentService(minioClient, documentRepository, chunkRepository,
            etlService, progressWriter, chunkCleanupService, metrics, publisherProvider(publisher),
            graphPublisherProvider(null), emptyGraphGatewayProvider());
        when(progressWriter.andThen(any())).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(doc(TENANT, DocumentStatus.SUCCESS)));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);

        service.reparse(DOC_ID, TENANT, null);
        ArgumentCaptor<java.util.function.Consumer<EtlProgress>> captor = callbackCaptor();
        verify(etlService).process(eq(DOC_ID), captor.capture(), any());

        captor.getValue().accept(new EtlProgress(DOC_ID, EtlStage.COMPLETED));
        verify(publisher).publish(TENANT, DOC_ID);

        captor.getValue().accept(new EtlProgress(DOC_ID, EtlStage.FAILED));
        verifyNoMoreInteractions(publisher);
    }

    /** 图谱网关 provider 桩（簇④）：缺省缺位形态（关闭态零变化） */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphGateway> emptyGraphGatewayProvider() {
        return mock(ObjectProvider.class);
    }

    /** 图谱抽取派发器 provider 桩（簇④）：缺省缺位 = null 直传（关闭态零变化） */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<GraphExtractionPublisher> graphPublisherProvider(GraphExtractionPublisher publisher) {
        ObjectProvider<GraphExtractionPublisher> provider = mock(ObjectProvider.class);
        if (publisher != null) {
            org.mockito.Mockito.doAnswer(inv -> {
                ((java.util.function.Consumer<GraphExtractionPublisher>) inv.getArgument(0)).accept(publisher);
                return null;
            }).when(provider).ifAvailable(any());
        }
        return provider;
    }

    /** 失效发布器 ObjectProvider 测试装配：publisher=null 即缺省关形态（ifAvailable 空转） */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<CacheInvalidationPublisher> publisherProvider(CacheInvalidationPublisher publisher) {
        ObjectProvider<CacheInvalidationPublisher> provider = mock(ObjectProvider.class);
        if (publisher != null) {
            org.mockito.Mockito.doAnswer(inv -> {
                ((java.util.function.Consumer<CacheInvalidationPublisher>) inv.getArgument(0)).accept(publisher);
                return null;
            }).when(provider).ifAvailable(any());
        }
        return provider;
    }

    @Test
    void reparseFutureCompletesFalseOnFailedFrame() {
        when(progressWriter.andThen(any())).thenAnswer(inv -> inv.getArgument(0));
        KbDocument document = doc(TENANT, DocumentStatus.SUCCESS);
        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(documentRepository.acquireForReindex(anyString(), any(), anyList())).thenReturn(1);

        java.util.concurrent.CompletableFuture<Boolean> outcome = service.reparse(DOC_ID, TENANT, null);

        ArgumentCaptor<java.util.function.Consumer<EtlProgress>> captor = callbackCaptor();
        verify(etlService).process(eq(DOC_ID), captor.capture(), any());

        captor.getValue().accept(new EtlProgress(DOC_ID, EtlStage.FAILED));
        assertThat(outcome).isCompletedWithValue(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<java.util.function.Consumer<EtlProgress>> callbackCaptor() {
        return ArgumentCaptor.forClass((Class) java.util.function.Consumer.class);
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

    // ── 上传大小守卫（安全簇② B2）：Service 层复核兜底，413 语义经 GlobalExceptionHandler ──

    @Test
    void uploadOversizedFileRejectedBeforeMinio() {
        MultipartFile oversized = sizedFile(DocumentService.MAX_FILE_SIZE_BYTES + 1, "application/pdf");

        assertThatThrownBy(() -> service.upload(oversized, TENANT, "u-1", null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("FILE_TOO_LARGE");
        verifyNoInteractions(minioClient);
        verifyNoInteractions(documentRepository);
    }

    @Test
    void uploadAtLimitBoundaryPassesSizeGuard() throws Exception {
        // 边界语义：等于上限放行（> 拒绝）
        MultipartFile atLimit = sizedFile(DocumentService.MAX_FILE_SIZE_BYTES, "application/pdf");

        service.upload(atLimit, TENANT, "u-1", null);

        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(documentRepository).save(any(KbDocument.class));
    }

    /** 轻量 MultipartFile 桩：仅伪造 size，不占 50MB 实体内存 */
    private static MultipartFile sizedFile(long size, String contentType) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return "big.pdf"; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return false; }
            @Override public long getSize() { return size; }
            @Override public byte[] getBytes() { return new byte[0]; }
            @Override public InputStream getInputStream() { return ByteArrayInputStream.nullInputStream(); }
            @Override public void transferTo(File dest) { throw new UnsupportedOperationException(); }
        };
    }

    // ── 上传格式白名单与类型映射（簇⑦ 4.14：PPTX/XLSX 扩容）──

    /** 白名单内类型放行（含 4.14 新增 PPTX/XLSX），类型映射落库正确 */
    @ParameterizedTest
    @CsvSource({
        "application/pdf, PDF",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document, DOCX",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation, PPTX",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, XLSX",
        "text/markdown, MD",
        "text/plain, TXT",
        "text/html, HTML"
    })
    void uploadAllowedTypePassesAndMapsCorrectly(String contentType, String expectedType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "样本", contentType, "内容".getBytes());

        service.upload(file, TENANT, "u-1", null);

        ArgumentCaptor<KbDocument> captor = ArgumentCaptor.forClass(KbDocument.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(expectedType);
    }

    /** 白名单外类型拒绝（含旧二进制格式 .ppt/.xls/.doc——与既有纪律一致仅收 OOXML 新格式） */
    @ParameterizedTest
    @ValueSource(strings = {
        "application/vnd.ms-powerpoint",       // .ppt 旧格式
        "application/vnd.ms-excel",            // .xls 旧格式
        "application/msword",                  // .doc 旧格式
        "application/octet-stream"             // 未知二进制
    })
    void uploadLegacyOrUnknownTypeRejected(String contentType) {
        MockMultipartFile file = new MockMultipartFile("file", "样本", contentType, "内容".getBytes());

        assertThatThrownBy(() -> service.upload(file, TENANT, "u-1", null))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode").isEqualTo("FILE_TYPE_UNSUPPORTED");
        verifyNoInteractions(minioClient);
        verify(documentRepository, never()).save(any());
    }
}
