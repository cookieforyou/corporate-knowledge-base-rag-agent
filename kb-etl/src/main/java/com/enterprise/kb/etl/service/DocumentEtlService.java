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
import com.enterprise.kb.etl.transformer.ContextualEnrichmentTransformer;
import com.enterprise.kb.etl.transformer.HtmlProtectingSplitter;
import com.enterprise.kb.etl.transformer.SanitizingTransformer;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 文档 ETL 服务 — 智能路由解析（2.1）→ 保护式切分（2.3，簇④ A4 起含 heading 路径）
 * → 安全消毒（簇② B1）→ 语境增强（簇④ A4，默认关）→ PG 落库 → 向量化 → ES 双写
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
    /** 语境增强器（kb.etl.contextual.enabled=true 才有 Bean，缺省 absent 即跳过） */
    private final ObjectProvider<ContextualEnrichmentTransformer> contextualEnrichmentProvider;

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

            // Stage 2.6: 语境增强（簇④ A4，9.5，默认关）：文档级语境前缀，content 存
            // 增强文本 / original_content 存原文。位于消毒之后——LLM 只见脱敏态文本；
            // 单 chunk 生成失败原样放行不阻断（质量项非必需项）
            ContextualEnrichmentTransformer enrichment = contextualEnrichmentProvider.getIfAvailable();
            if (enrichment != null) {
                attachDocExcerpt(chunks, doc, rawDocs);
                chunks = enrichment.apply(chunks);
            }

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

    /** 语境增强的文档概要取前字符数（kb.etl.contextual.excerpt-chars，簇④ A4） */
    @Value("${kb.etl.contextual.excerpt-chars:2000}")
    private int contextualExcerptChars;

    /**
     * 语境增强的文档概要注入：首段非空解析文本取前 N 字符（含文档标题行），
     * 写入每个 chunk 的 {@code doc_excerpt} 元数据供增强 prompt 消费
     * （同一文档全部 chunk 共享同一概要——Prompt Caching 摊薄成本的形态基础）。
     */
    private void attachDocExcerpt(List<Document> chunks, KbDocument doc, List<Document> rawDocs) {
        String firstText = rawDocs.stream()
            .map(Document::getText)
            .filter(t -> t != null && !t.isBlank())
            .findFirst().orElse("");
        String excerpt = "文档标题：" + (doc.getName() != null ? doc.getName() : "未知文档") + "\n"
            + (firstText.length() <= contextualExcerptChars
                ? firstText : firstText.substring(0, contextualExcerptChars));
        for (Document chunk : chunks) {
            // splitter 各冲刷组元数据相互独立，同值写入幂等安全
            chunk.getMetadata().put(ContextualEnrichmentTransformer.DOC_EXCERPT_KEY, excerpt);
        }
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
            // heading 路径（簇④ A4）：载体字段供向量化/ES 消费，持久化入 metadata JSONB
            Object headingPath = chunk.getMetadata().get(HtmlProtectingSplitter.HEADING_PATH_KEY);
            if (headingPath instanceof String hp && !hp.isBlank()) {
                entity.setHeadingPath(hp);
            }
            // 语境增强原文（簇④ A4）：content 已是增强文本，原文落 original_content
            // （TABLE/IMAGE 的 original_content 已被 original_html 占用，不覆盖）
            Object originalText = chunk.getMetadata().get(ContextualEnrichmentTransformer.ORIGINAL_TEXT_KEY);
            if (entity.getOriginalContent() == null && originalText instanceof String ot && !ot.isBlank()) {
                entity.setOriginalContent(ot);
            }
            // 从 Tika metadata 提取页码
            Object page = chunk.getMetadata().get("page_number");
            if (page instanceof Integer pi) entity.setPageNum(pi);
            else if (page != null) {
                try { entity.setPageNum(Integer.valueOf(page.toString())); } catch (Exception ignored) {}
            }
            // metadata JSONB：注入命中标记（簇② B1 S4）+ heading 路径（簇④ A4）
            Map<String, Object> metaJson = new LinkedHashMap<>();
            if (Boolean.TRUE.equals(chunk.getMetadata().get(SanitizingTransformer.INJECTION_HIT_KEY))) {
                metaJson.put(SanitizingTransformer.INJECTION_HIT_KEY, true);
            }
            if (entity.getHeadingPath() != null) {
                metaJson.put(HtmlProtectingSplitter.HEADING_PATH_KEY, entity.getHeadingPath());
            }
            if (!metaJson.isEmpty()) {
                entity.setMetadata(toMetadataJson(metaJson));
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
                .map(e -> {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("chunk_id", e.getId());
                    meta.put("doc_id", doc.getId());
                    meta.put("tenant_id", doc.getTenantId());
                    meta.put("chunk_type", e.getChunkType().name());
                    // file_name 随向量元数据携带（2.14 调试台/溯源展示；
                    // 存量向量缺此字段，重新入库后补齐）
                    meta.put("file_name", doc.getName() != null ? doc.getName() : "unknown");
                    meta.put("page_num", e.getPageNum() != null ? e.getPageNum() : 0);
                    meta.put("is_deleted", Objects.requireNonNullElse(e.getIsDeleted(), false));
                    // heading 路径（簇④ A4）：调试台/溯源展示与后续检索消费；
                    // 元数据禁 null（Spring AI 约束），缺省不写键
                    if (e.getHeadingPath() != null) {
                        meta.put(HtmlProtectingSplitter.HEADING_PATH_KEY, e.getHeadingPath());
                    }
                    return new Document(e.getId(), e.getContent(), meta);
                })
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
