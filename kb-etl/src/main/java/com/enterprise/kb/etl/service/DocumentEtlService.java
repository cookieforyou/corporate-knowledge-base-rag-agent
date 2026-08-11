package com.enterprise.kb.etl.service;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.ChunkType;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgress;
import com.enterprise.kb.etl.pipeline.EtlStage;
import com.enterprise.kb.etl.reader.SmartParsingRouter;
import com.enterprise.kb.etl.transformer.HtmlProtectingSplitter;
import com.enterprise.kb.etl.transformer.SanitizingTransformer;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 文档 ETL 服务 — 智能路由解析（2.1）→ 保护式切分（2.3）→ 安全消毒（簇② B1）
 * → PG 落库 → 向量化 → ES 双写
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentEtlService {

    private final MinioClient minioClient;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final VectorStore vectorStore;
    private final EsIndexWriter esIndexWriter;
    private final SmartParsingRouter parsingRouter;
    private final HtmlProtectingSplitter protectingSplitter;
    private final SanitizingTransformer sanitizingTransformer;
    private final JsonMapper jsonMapper;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 异步执行 ETL 管道（自动解析路由）
     *
     * @param docId            文档 ID
     * @param progressCallback 进度回调（可选，Phase 1 传空函数即可）
     */
    @Async("etlExecutor")
    public void process(String docId, Consumer<EtlProgress> progressCallback) {
        process(docId, progressCallback, null);
    }

    /**
     * 异步执行 ETL 管道（可指定解析路由）
     *
     * @param forcedRoute 上传参数强制指定的解析路由（null = SmartParsingRouter 自动决策）
     */
    @Async("etlExecutor")
    public void process(String docId, Consumer<EtlProgress> progressCallback, ParseRoute forcedRoute) {
        KbDocument doc = documentRepository.findById(docId)
            .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "文档不存在: " + docId));

        try {
            // 更新状态为解析中
            doc.setStatus(DocumentStatus.PARSING);
            documentRepository.save(doc);

            // Stage 1: MinIO 读取 + 智能路由解析（NATIVE/DEEP/OCR，9.1）
            progressCallback.accept(new EtlProgress(docId, EtlStage.READING));
            SmartParsingRouter.ParsingOutcome outcome = readAndParse(doc, forcedRoute);
            List<Document> rawDocs = outcome.documents();
            doc.setParseRoute(outcome.route().name());
            doc.setPageCount(pageCountOf(rawDocs));
            doc.setTableCount(countOf(rawDocs, "table_count"));
            doc.setImageCount(countOf(rawDocs, "image_count"));
            log.info("文档解析完成: docId={}, route={}, pages={}, tables={}, images={}",
                docId, outcome.route(), doc.getPageCount(), doc.getTableCount(), doc.getImageCount());

            // Stage 2: 保护式切分（表格/图片保护，无保护标签走快速路径 = Phase 1 行为）
            progressCallback.accept(new EtlProgress(docId, EtlStage.TRANSFORMING));
            List<Document> chunks = protectingSplitter.apply(rawDocs);
            log.info("文档切分完成: docId={}, chunks={}", docId, chunks.size());

            // Stage 2.5: 安全消毒（簇② B1，12.4.2 第一道纵深）：PII 掩码 + 注入打标，
            // 落库面向量库/ES 均为脱敏态；命中标记经元数据流入 kb_chunk.metadata
            chunks = sanitizingTransformer.apply(chunks);

            // Stage 3: 落 kb_chunk 表
            progressCallback.accept(new EtlProgress(docId, EtlStage.PERSISTING));
            List<KbChunk> entities = persistChunks(docId, chunks);

            // Stage 4: 向量化 + 写入 VectorStore
            progressCallback.accept(new EtlProgress(docId, EtlStage.EMBEDDING));
            embedAndStore(doc, entities);

            // Stage 5: ES 双写（v2 2.5，混合检索的 BM25 数据源；
            //          失败不阻断 ETL——PG 为事实源，索引重建任务可兜底）
            progressCallback.accept(new EtlProgress(docId, EtlStage.INDEXING));
            esIndexWriter.indexChunks(doc, entities);

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

    private SmartParsingRouter.ParsingOutcome readAndParse(KbDocument doc, ParseRoute forcedRoute)
            throws Exception {
        // 从 MinIO 下载文件字节
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        minioClient.getObject(GetObjectArgs.builder()
            .bucket(bucket)
            .object(doc.getOssPath())
            .build()).transferTo(bos);

        // 智能路由解析（NATIVE=Tika / DEEP=DocMind / OCR=qwen3.5-ocr，9.1）
        return parsingRouter.read(bos.toByteArray(), doc.getName(), forcedRoute);
    }

    /** 页数：深度链路经元数据携带（解析服务回报），NATIVE 按 Tika 文档段数 */
    private static int pageCountOf(List<Document> rawDocs) {
        if (!rawDocs.isEmpty()) {
            Object pages = rawDocs.get(0).getMetadata().get("page_count");
            if (pages instanceof Number n && n.intValue() > 0) {
                return n.intValue();
            }
        }
        return rawDocs.size();
    }

    /** 解析统计（table_count/image_count）：深度链路经元数据携带，NATIVE 无则 null */
    private static Integer countOf(List<Document> rawDocs, String key) {
        if (!rawDocs.isEmpty()) {
            Object value = rawDocs.get(0).getMetadata().get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    private List<KbChunk> persistChunks(String docId, List<Document> chunks) {
        List<KbChunk> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            KbChunk entity = new KbChunk();
            String chunkId = UUID.randomUUID().toString();
            entity.setId(chunkId);
            entity.setVectorId(chunkId);
            entity.setDocId(docId);
            entity.setChunkIndex(i);
            entity.setContent(chunk.getText());
            // chunk_type 由切分器标注（2.3 保护式切分：TABLE/IMAGE；缺省 TEXT）
            Object chunkType = chunk.getMetadata().get("chunk_type");
            entity.setChunkType(parseChunkType(chunkType));
            // 保护块原文 HTML（TABLE/IMAGE 回显与结构保真，9.2）
            Object originalHtml = chunk.getMetadata().get("original_html");
            if (originalHtml != null) {
                entity.setOriginalContent(originalHtml.toString());
            }
            // 从 Tika metadata 提取页码
            Object page = chunk.getMetadata().get("page_number");
            if (page instanceof Integer pi) entity.setPageNum(pi);
            else if (page != null) {
                try { entity.setPageNum(Integer.valueOf(page.toString())); } catch (Exception ignored) {}
            }
            // 注入扫描命中标记落 metadata JSONB（簇② B1 S4）：供运维处置/后续门禁消费
            if (Boolean.TRUE.equals(chunk.getMetadata().get(SanitizingTransformer.INJECTION_HIT_KEY))) {
                entity.setMetadata(toMetadataJson(Map.of(SanitizingTransformer.INJECTION_HIT_KEY, true)));
            }
            // 估算 token 数（中文约 1.5 字符/token，英文约 4 字符/token）
            entity.setTokenCount((int) (chunk.getText().length() / 2.5));
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            entities.add(entity);
        }
        chunkRepository.saveAll(entities);
        return entities;
    }

    /**
     * 单批 embedding 条数上限（kb.etl.embed-batch-size，默认 10；簇① A3 配置化）。
     *
     * <p><b>2026-08-03 E2E 缺陷</b>：DashScope embedding API（qwen3.7-text-embedding）
     * 单次请求硬限制 ≤20 条输入（超出 400 InvalidParameter）。VectorStore 内部的
     * TokenCountBatchingStrategy 只按 token 预算分批、<em>不限制单批条数</em>——
     * 页级切分（2026-08-03）产出的小 chunk 会被密集打包进同一批超限被拒
     * （30 chunk 偶发通过、35 chunk 触发，纯随 chunk 大小分布侥幸）。
     * 故 ETL 侧按固定条数分批，双向量库后端（Milvus/pgvector）统一受保护。
     * 调整上限不得超过 20（供应商硬限制）。
     */
    @Value("${kb.etl.embed-batch-size:10}")
    private int embedBatchSize;

    /**
     * 向量化并写入 VectorStore（pgvector 或 Milvus，取决于配置），按 {@link #embedBatchSize} 分批
     */
    private void embedAndStore(KbDocument doc, List<KbChunk> entities) {
        int total = entities.size();
        for (int from = 0; from < total; from += embedBatchSize) {
            List<KbChunk> batch = entities.subList(from, Math.min(from + embedBatchSize, total));
            List<Document> vectorDocs = batch.stream()
                .map(e -> new Document(e.getId(), e.getContent(),
                    Map.of("chunk_id", e.getId(),
                           "doc_id", doc.getId(),
                           "tenant_id", doc.getTenantId(),
                           "chunk_type", e.getChunkType().name(),
                           // file_name 随向量元数据携带（2.14 调试台/溯源展示；
                           // 存量向量缺此字段，重新入库后补齐）
                           "file_name", doc.getName() != null ? doc.getName() : "unknown",
                           "page_num", e.getPageNum() != null ? e.getPageNum() : 0,
                           "is_deleted", Objects.requireNonNullElse(e.getIsDeleted(), false))))
                .toList();

            vectorStore.add(vectorDocs);
            log.debug("向量化分批写入: docId={}, range=[{}, {})", doc.getId(), from, from + batch.size());
        }
        log.info("向量化写入完成: docId={}, vectors={}", doc.getId(), total);
    }

    /** chunk 元数据 JSON 序列化：失败回退空对象（标记缺失不阻断入库） */
    private String toMetadataJson(Map<String, Object> metadata) {
        try {
            return jsonMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("chunk 元数据 JSON 序列化失败，回退空对象: {}", e.getMessage());
            return "{}";
        }
    }

    private static ChunkType parseChunkType(Object value) {
        if (value == null) {
            return ChunkType.TEXT;
        }
        try {
            return ChunkType.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return ChunkType.TEXT;
        }
    }
}
