package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.DocumentService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理 Controller —— 上传 + 列表 + 详情 + Chunk 观测 + 删除（2.15 文档管理界面）
 *
 * <p>全部端点按 JWT 租户隔离（owner claim），跨租户访问返回业务异常。
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final JwtUtils jwtUtils;

    /** 上传文档；parseRoute 可选强制路由（NATIVE/DEEP/OCR，缺省自动决策，9.1） */
    @PostMapping("/upload")
    public ApiResponse<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "parseRoute", required = false) String parseRoute) {
        String docId = documentService.upload(file, jwtUtils.getCurrentTenantId(),
            jwtUtils.getCurrentUsername(), parseRoute);
        return ApiResponse.success(Map.of("docId", docId));
    }

    /** 当前租户的文档列表（按创建时间倒序） */
    @GetMapping
    public ApiResponse<List<KbDocument>> list() {
        return ApiResponse.success(documentService.listByTenant(jwtUtils.getCurrentTenantId()));
    }

    /** 文档详情 */
    @GetMapping("/{id}")
    public ApiResponse<KbDocument> detail(@PathVariable String id) {
        return ApiResponse.success(documentService.getOwned(id, jwtUtils.getCurrentTenantId()));
    }

    /** 文档的 Chunk 列表（Chunk 观测台数据源，按切分序号升序） */
    @GetMapping("/{id}/chunks")
    public ApiResponse<List<KbChunk>> chunks(@PathVariable String id) {
        return ApiResponse.success(documentService.chunksOfOwned(id, jwtUtils.getCurrentTenantId()));
    }

    /** 删除文档：PG 元数据/Chunk + 向量库 + ES 索引 + MinIO 对象级联清理 */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable String id) {
        documentService.delete(id, jwtUtils.getCurrentTenantId());
        return ApiResponse.success(Map.of("deleted", id));
    }
}
