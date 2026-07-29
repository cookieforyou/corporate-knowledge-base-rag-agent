package com.enterprise.kb.api.service;

import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 文档管理服务 — MinIO 上传 + PG 元数据落库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final MinioClient minioClient;
    private final KbDocumentRepository documentRepository;

    @Value("${minio.bucket}")
    private String bucket;

    /** 允许的文件类型 */
    private static final java.util.Set<String> ALLOWED_TYPES =
        java.util.Set.of("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/markdown", "text/plain", "text/html");

    /**
     * 上传文档：写入 MinIO → 落 kb_document 表 → 返回文档 ID
     */
    public String upload(MultipartFile file, String tenantId, String createdBy) {
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
        return docId;
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
