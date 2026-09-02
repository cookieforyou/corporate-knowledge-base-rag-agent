package com.enterprise.kb.api.controller;

import com.enterprise.kb.api.security.JwtUtils;
import com.enterprise.kb.api.service.DocumentService;
import com.enterprise.kb.commons.dto.ApiResponse;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理 Controller —— 上传 + 列表 + 详情 + Chunk 观测 + 删除（2.15 文档管理界面）
 *
 * <p>全部端点按 JWT 租户隔离（owner claim），跨租户访问返回业务异常。
 *
 * <p><b>写操作分级</b>（前端鉴权批，2026-09-02）：上传保留全员（贡献者语义）；
 * 删除/重解析/替换为知识库治理写（与 Chunk 运维同权重），经
 * {@code @PreAuthorize} 限超管（isAdmin claim → ROLE_ADMIN，映射见 SecurityConfig）。
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

    /** 删除文档：PG 元数据/Chunk + 向量库 + ES 索引 + MinIO 对象级联清理（仅超管） */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, String>> delete(@PathVariable String id) {
        documentService.delete(id, jwtUtils.getCurrentTenantId());
        return ApiResponse.success(Map.of("deleted", id));
    }

    /**
     * 增量重入库 — 重解析（簇⑥ C1）：以 MinIO 现有原件重走 ETL；
     * 蓝绿语义（先写后删 diff），成功后 version+1。仅 SUCCESS/FAILED 态可发起。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reparse")
    public ApiResponse<Map<String, String>> reparse(
            @PathVariable String id,
            @RequestParam(value = "parseRoute", required = false) String parseRoute) {
        documentService.reparse(id, jwtUtils.getCurrentTenantId(), parseRoute);
        return ApiResponse.success(Map.of("docId", id, "status", "REINDEXING"));
    }

    /**
     * 增量重入库 — 替换（簇⑥ C1）：新文件覆盖原件后重走 ETL（文档更新场景）；
     * 路由缺省自动决策（新文件不复用旧版本路由）。仅 SUCCESS/FAILED 态可发起。
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/replace")
    public ApiResponse<Map<String, String>> replace(
            @PathVariable String id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "parseRoute", required = false) String parseRoute) {
        documentService.replace(id, jwtUtils.getCurrentTenantId(), file, parseRoute);
        return ApiResponse.success(Map.of("docId", id, "status", "REINDEXING"));
    }
}
