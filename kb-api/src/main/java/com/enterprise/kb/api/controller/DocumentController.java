package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.service.DocumentService;
import com.enterprise.kb.commons.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档管理 Controller
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     *
     * @param file      文件（PDF/Docx/MD/TXT/HTML）
     * @param tenantId  租户 ID（Header 传入，Phase 1 默认 "default"）
     * @param createdBy 创建者（Header 传入，Phase 1 默认 "system"）
     */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String createdBy) {

        String docId = documentService.upload(file, tenantId, createdBy);
        return ApiResponse.success(Map.of("docId", docId));
    }
}
