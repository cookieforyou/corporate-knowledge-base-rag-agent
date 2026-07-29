package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.security.JwtUtils;
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
    private final JwtUtils jwtUtils;

    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        String docId = documentService.upload(file, jwtUtils.getCurrentTenantId(), jwtUtils.getCurrentUsername());
        return ApiResponse.success(Map.of("docId", docId));
    }
}
