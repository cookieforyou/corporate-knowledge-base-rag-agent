package com.enterprise.kb.etl.service;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgress;
import com.enterprise.kb.etl.pipeline.EtlStage;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 文档 ETL 服务 — Tika 解析 → Token 切分 → PG 落库 → 向量化写入
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEtlService {

    private final MinioClient minioClient;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final VectorStore vectorStore;

    @Value("${minio.bucket}")
    private String bucket;

    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder()
        .withChunkSize(800)
        .withMinChunkSizeChars(200)
        .withMinChunkLengthToEmbed(10)
        .withMaxNumChunks(5)
        .withKeepSeparator(true)
        .build();

    /**
     * 异步执行 ETL 管道
     *
     * @param docId            文档 ID
     * @param progressCallback 进度回调（可选，Phase 1 传空函数即可）
     */
    @Async("etlExecutor")
    public void process(String docId, Consumer<EtlProgress> progressCallback) {
        KbDocument doc = documentRepository.findById(docId)
            .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "文档不存在: " + docId));

        try {
            // 更新状态为解析中
            doc.setStatus(DocumentStatus.PARSING);
            documentRepository.save(doc);

            // Stage 1: 从 MinIO 读取并 Tika 解析
            progressCallback.accept(new EtlProgress(docId, EtlStage.READING));
            List<Document> rawDocs = readAndParse(doc);
            log.info("文档解析完成: docId={}, pages={}", docId, rawDocs.size());

            // Stage 2: Token 切分
            progressCallback.accept(new EtlProgress(docId, EtlStage.TRANSFORMING));
            List<Document> chunks = textSplitter.apply(rawDocs);
            log.info("文档切分完成: docId={}, chunks={}", docId, chunks.size());

            // Stage 3: 落 kb_chunk 表
            progressCallback.accept(new EtlProgress(docId, EtlStage.PERSISTING));
            List<KbChunk> entities = persistChunks(docId, chunks);

            // Stage 4: 向量化 + 写入 VectorStore
            progressCallback.accept(new EtlProgress(docId, EtlStage.EMBEDDING));
            embedAndStore(doc, entities);

            // 更新文档状态
            doc.setStatus(DocumentStatus.SUCCESS);
            doc.setChunkCount(chunks.size());
            documentRepository.save(doc);

            EtlProgress done = new EtlProgress(docId, EtlStage.COMPLETED);
            done.setProcessedChunks(chunks.size());
            done.setPercentage(100);
            progressCallback.accept(done);

            log.info("ETL 完成: docId={}, chunks={}", docId, chunks.size());

        } catch (Exception e) {
            log.error("ETL 失败: docId={}", docId, e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            progressCallback.accept(new EtlProgress(docId, EtlStage.FAILED));
            throw new BusinessException("ETL_FAILED", "文档处理失败: " + e.getMessage(), e);
        }
    }

    // ── 私有方法 ──

    private List<Document> readAndParse(KbDocument doc) throws Exception {
        // 从 MinIO 下载文件字节
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        minioClient.getObject(GetObjectArgs.builder()
            .bucket(bucket)
            .object(doc.getOssPath())
            .build()).transferTo(bos);

        // Tika 自动检测格式并解析
        TikaDocumentReader reader = new TikaDocumentReader(
            new ByteArrayResource(bos.toByteArray()));
        return reader.get();
    }

    private List<KbChunk> persistChunks(String docId, List<Document> chunks) {
        List<KbChunk> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            KbChunk entity = new KbChunk();
            entity.setId(UUID.randomUUID().toString());
            entity.setDocId(docId);
            entity.setChunkIndex(i);
            entity.setContent(chunk.getText());
            entity.setChunkType(ChunkType.TEXT);
            entities.add(entity);
        }
        chunkRepository.saveAll(entities);
        return entities;
    }

    /**
     * 向量化并写入 VectorStore（pgvector 或 Milvus，取决于配置）
     */
    private void embedAndStore(KbDocument doc, List<KbChunk> entities) {
        List<Document> vectorDocs = entities.stream()
            .map(e -> {
                Document d = new Document(e.getId(), e.getContent(),
                    Map.of("chunk_id", e.getId(),
                           "doc_id", doc.getId(),
                           "tenant_id", doc.getTenantId(),
                           "chunk_type", e.getChunkType().name()));
                return d;
            })
            .toList();

        // VectorStore.add() 内部自动调用 EmbeddingModel 完成向量化，以 Document.id 作为 vector_id
        vectorStore.add(vectorDocs);
        log.info("向量化写入完成: docId={}, vectors={}", doc.getId(), vectorDocs.size());

        // 回写 vector_id（与 chunk_id 一致）
        for (KbChunk entity : entities) {
            entity.setVectorId(entity.getId());
        }
        chunkRepository.saveAll(entities);
    }
}
