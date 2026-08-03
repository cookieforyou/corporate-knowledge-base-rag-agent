package com.enterprise.kb.api.service;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgressRedisWriter;
import com.enterprise.kb.etl.service.DocumentEtlService;
import com.enterprise.kb.etl.writer.EsIndexWriter;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 文档管理服务 — MinIO 上传 + PG 元数据落库 + 生命周期管理（2.15）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final MinioClient minioClient;
    private final KbDocumentRepository documentRepository;
    private final KbChunkRepository chunkRepository;
    private final DocumentEtlService etlService;
    private final EtlProgressRedisWriter progressWriter;
    private final VectorStore vectorStore;
    private final EsIndexWriter esIndexWriter;

    @Value("${minio.bucket}")
    private String bucket;

    /** 允许的文件类型 */
    private static final java.util.Set<String> ALLOWED_TYPES =
        java.util.Set.of("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown", "text/plain", "text/html");

    /**
     * 上传文档：写入 MinIO → 落 kb_document 表 → 返回文档 ID
     *
     * @param parseRoute 强制指定解析路由（NATIVE/DEEP/OCR，null = 自动决策，9.1）
     */
    public String upload(MultipartFile file, String tenantId, String createdBy, String parseRoute) {
        validateFile(file);

        String docId = UUID.randomUUID().toString();
        String ossPath = docId + "/" + file.getOriginalFilename();

        // 1. 上传到 MinIO
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(ossPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build()
            );
            log.info("文档已上传 MinIO: bucket={}, path={}", bucket, ossPath);
        } catch (Exception e) {
            throw new BusinessException("UPLOAD_FAILED", "文件上传失败: " + e.getMessage(), e);
        }

        // 2. 落库
        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setName(file.getOriginalFilename());
        doc.setOriginalName(file.getOriginalFilename());
        doc.setType(extractFileType(file.getContentType()));
        doc.setSize(file.getSize());
        doc.setOssPath(ossPath);
        doc.setStatus(DocumentStatus.UPLOADING);
        doc.setCreatedBy(createdBy);
        documentRepository.save(doc);

        log.info("文档元数据已落库: id={}, name={}", docId, file.getOriginalFilename());

        // 3. 触发异步 ETL（进度双通道：Redis Hash 状态 + Pub/Sub 实时推送 WebSocket，9.6/2.13）
        etlService.process(docId, progressWriter.andThen(p ->
                log.debug("ETL 进度: docId={}, stage={}, pct={}", p.getDocId(), p.getStage(), p.getPercentage())),
            parseForcedRoute(parseRoute));

        return docId;
    }

    /** 当前租户文档列表（按创建时间倒序） */
    public List<KbDocument> listByTenant(String tenantId) {
        return documentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /** 租户所有权校验的文档详情 */
    public KbDocument getOwned(String docId, String tenantId) {
        KbDocument doc = documentRepository.findById(docId)
            .orElseThrow(() -> new BusinessException("DOC_NOT_FOUND", "文档不存在: " + docId));
        checkOwnership(doc, tenantId);
        return doc;
    }

    /** 租户所有权校验的 Chunk 列表（观测台数据源） */
    public List<KbChunk> chunksOfOwned(String docId, String tenantId) {
        getOwned(docId, tenantId);
        return chunkRepository.findByDocIdOrderByChunkIndex(docId);
    }

    /**
     * 删除文档（级联清理）：PG Chunk/文档行 → 向量库 → ES 索引 → MinIO 对象。
     * 下游存储清理为尽力而为（失败告警不阻断主删除——PG 为事实源，残留可离线清理）。
     *
     * <p><b>幂等</b>（2026-08-03）：删除不存在的文档静默成功。E2E 实测删除竞态
     * （重复点击/双 Tab/列表刷新前再删）会产生第二次 DELETE，此前抛 DOC_NOT_FOUND
     * 业务异常——对删除语义而言「目标已不存在」即「已删除」，不应报错。
     */
    public void delete(String docId, String tenantId) {
        KbDocument doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            log.info("文档不存在或已删除，跳过（幂等）: docId={}", docId);
            return;
        }
        checkOwnership(doc, tenantId);

        List<String> chunkIds = chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().map(KbChunk::getId).toList();
        chunkRepository.deleteAllById(chunkIds);

        try {
            if (!chunkIds.isEmpty()) {
                vectorStore.delete(chunkIds);   // vectorId = chunkId（9.3 不变量）
            }
        } catch (Exception e) {
            log.warn("向量库清理失败（不阻断删除）: docId={}, {}", docId, e.getMessage());
        }
        try {
            esIndexWriter.deleteByDocId(docId);
        } catch (Exception e) {
            log.warn("ES 索引清理失败（不阻断删除）: docId={}, {}", docId, e.getMessage());
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket).object(doc.getOssPath()).build());
        } catch (Exception e) {
            log.warn("MinIO 对象清理失败（不阻断删除）: docId={}, {}", docId, e.getMessage());
        }

        documentRepository.delete(doc);
        log.info("文档已删除: id={}, name={}, chunks={}", docId, doc.getName(), chunkIds.size());
    }

    private void checkOwnership(KbDocument doc, String tenantId) {
        if (!tenantId.equals(doc.getTenantId())) {
            throw new BusinessException("DOC_FORBIDDEN", "无权访问该文档");
        }
    }

    /** 解析路由参数解析：非法值视为未指定（自动决策），不阻断上传 */
    private static ParseRoute parseForcedRoute(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ParseRoute.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无法识别的解析路由参数（按自动决策处理）: {}", raw);
            return null;
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "上传文件为空");
        }
        if (file.getContentType() != null && !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new BusinessException("FILE_TYPE_UNSUPPORTED",
                "不支持的文件类型: " + file.getContentType() + "，仅支持 PDF/Docx/MD/TXT/HTML");
        }
    }

    private String extractFileType(String contentType) {
        if (contentType == null) return "UNKNOWN";
        return switch (contentType) {
            case "application/pdf" -> "PDF";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX";
            case "text/markdown" -> "MD";
            case "text/plain" -> "TXT";
            case "text/html" -> "HTML";
            default -> "UNKNOWN";
        };
    }
}
