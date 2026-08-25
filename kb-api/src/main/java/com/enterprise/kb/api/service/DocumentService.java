package com.enterprise.kb.api.service;

import com.enterprise.kb.ai.cache.CacheInvalidationPublisher;
import com.enterprise.kb.etl.pipeline.graph.GraphExtractionPublisher;
import com.enterprise.kb.ai.metrics.AiBusinessMetrics;
import com.enterprise.kb.commons.exception.BusinessException;
import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.pipeline.EtlProgressRedisWriter;
import com.enterprise.kb.etl.pipeline.EtlStage;
import com.enterprise.kb.etl.service.ChunkCleanupService;
import com.enterprise.kb.etl.service.DocumentEtlService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 文档管理服务 — MinIO 上传 + PG 元数据落库 + 生命周期管理（2.15）
 * + 增量重入库 reparse/replace（簇⑥ C1）
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
    private final ChunkCleanupService chunkCleanupService;
    private final AiBusinessMetrics metrics;
    /** 语义缓存失效发布器（簇③ 5.6 批2）：缺省关时 Bean 缺位，ObjectProvider 容忍 */
    private final ObjectProvider<CacheInvalidationPublisher> cacheInvalidationPublisher;
    /** 图谱抽取派发器（簇④ 5.1 批2）：缺省关时 Bean 缺位，ObjectProvider 容忍 */
    private final ObjectProvider<GraphExtractionPublisher> graphExtractionPublisher;
    /** 图谱网关（簇④ 批3）：文档删除时尽力清理图引用；缺省关时 Bean 缺位 */
    private final ObjectProvider<com.enterprise.kb.infrastructure.graph.GraphGateway> graphGateway;

    @Value("${minio.bucket}")
    private String bucket;

    /** 允许的文件类型（4.14：PPTX/XLSX 扩容——与既有白名单同纪律仅收 OOXML 新格式，
     *  旧二进制格式 .ppt/.xls/.doc 不收，企业侧先转存；解析经 NATIVE Tika 天然兼容） */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "text/markdown", "text/plain", "text/html",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /**
     * 单文件上传上限（安全簇② B2，2026-08-17 定案 50MB）：与
     * spring.servlet.multipart.max-file-size 同值——Servlet 层先行拦截（413），
     * Service 层复核兜底（防配置漂移），双层同语义。
     */
    static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

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
     *
     * <p><b>处理期守卫</b>（2026-08-13，簇⑥ C1 收尾）：UPLOADING/PARSING/REINDEXING
     * 期间禁止删除 → DOC_NOT_READY(409)。级联清理与在途 ETL 竞态会产生孤儿写回，
     * 重入库窗口内误删更会摧毁正在重入库的文档；SUCCESS/FAILED 放行（FAILED 删除
     * 是正当清理路径）。前端删除按钮同状态集 disable（Documents.vue）。
     */
    public void delete(String docId, String tenantId) {
        KbDocument doc = documentRepository.findById(docId).orElse(null);
        if (doc == null) {
            log.info("文档不存在或已删除，跳过（幂等）: docId={}", docId);
            return;
        }
        checkOwnership(doc, tenantId);

        DocumentStatus status = doc.getStatus();
        if (status != null && status.isProcessing()) {
            throw new BusinessException("DOC_NOT_READY",
                "文档处理中，禁止删除（当前 " + status + "）: " + docId);
        }

        // 三库级联委派共享组件（簇⑥ C1）：PG chunk + 向量库 + ES deleteByQuery(doc_id)
        // 文档级扫尾形态——含 PG 外的 ES 孤儿一并清理
        List<String> chunkIds = chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().map(KbChunk::getId).toList();
        chunkCleanupService.physicalDelete(docId, chunkIds, true);

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket).object(doc.getOssPath()).build());
        } catch (Exception e) {
            log.warn("MinIO 对象清理失败（不阻断删除）: docId={}, {}", docId, e.getMessage());
        }

        // 图谱引用清理（簇④ 批3）：Chunk 锚点删除 + 实体/关系引用摘除 + 孤儿清扫
        // 尽力而为——图故障不阻断删除（残留引用经下次同文档重抽取/孤儿清扫收敛）
        graphGateway.ifAvailable(gateway -> {
            try {
                gateway.removeDocument(tenantId, docId);
            } catch (Exception e) {
                log.warn("图数据清理失败（不阻断删除）: docId={}, {}", docId, e.getMessage());
            }
        });

        documentRepository.delete(doc);
        log.info("文档已删除: id={}, name={}, chunks={}", docId, doc.getName(), chunkIds.size());
    }

    /**
     * 增量重入库 — 重解析（簇⑥ C1）：以 MinIO 现有原件重走 ETL 管线
     * （解析管线升级 / 单文档修复场景，无需新文件）。
     *
     * <p><b>状态守卫</b>：仅 SUCCESS/FAILED 可重入库，经 DB 级原子占用
     * （UPDATE ... WHERE status IN）防并发双占用；处理中/上传中 → DOC_NOT_READY(409)。
     * <p><b>路由语义</b>：显式参数 &gt; 文档留存的原始路由——重解析默认复现
     * 首次入库管线形态（parse_route 记录的是实际使用路由）。
     * <p>蓝绿清理与版本号由 ETL 管线统一承接（DocumentEtlService，先写后删 diff）。
     *
     * @return 重入库终态 future（簇③ 4.5 重建编排消费）：true = COMPLETED，
     *         false = FAILED；同步快速失败（占用/所有权）仍直接抛 BusinessException
     */
    public CompletableFuture<Boolean> reparse(String docId, String tenantId, String parseRoute) {
        KbDocument doc = getOwned(docId, tenantId);
        acquireForReindex(doc);
        ParseRoute route = firstRoute(parseRoute, doc.getParseRoute());
        metrics.recordReindexStarted();
        log.info("文档重解析已发起: docId={}, route={}", docId, route);
        CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        etlService.process(docId, reindexProgressCallback(doc.getName(), doc.getTenantId(), outcome), route);
        return outcome;
    }

    /**
     * 增量重入库 — 替换（簇⑥ C1）：新文件覆盖 MinIO 原件后重走 ETL（文档更新场景）。
     *
     * <p><b>顺序</b>：先原子占用（快速失败，避免无谓 MinIO 写入）→ 覆盖原件
     * （新文件名不同则新路径写入 + 尽力删旧对象）→ 元数据更新 → 触发 ETL。
     * 原件覆盖失败：占用已生效，落 FAILED + error_message（FAILED 态可重试
     * reparse——原件未被破坏，或重试 replace）。
     * <p><b>路由语义</b>：显式参数 &gt; 自动决策——新文件不复用旧版本路由
     * （文档内容已变，密度特征可能不同，与首次上传语义对齐）。
     */
    public void replace(String docId, String tenantId, MultipartFile file, String parseRoute) {
        KbDocument doc = getOwned(docId, tenantId);
        validateFile(file);
        acquireForReindex(doc);

        String newPath = docId + "/" + file.getOriginalFilename();
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(newPath)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            if (!newPath.equals(doc.getOssPath())) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(doc.getOssPath()).build());
                } catch (Exception e) {
                    log.warn("旧版本 MinIO 对象清理失败（不阻断）: docId={}, {}", docId, e.getMessage());
                }
            }
        } catch (Exception e) {
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage("替换原件失败: " + e.getMessage());
            documentRepository.save(doc);
            throw new BusinessException("UPLOAD_FAILED", "替换文件上传失败: " + e.getMessage(), e);
        }

        doc.setOssPath(newPath);
        doc.setName(file.getOriginalFilename());
        doc.setOriginalName(file.getOriginalFilename());
        doc.setType(extractFileType(file.getContentType()));
        doc.setSize(file.getSize());
        documentRepository.save(doc);

        ParseRoute route = firstRoute(parseRoute, null);
        metrics.recordReindexStarted();
        log.info("文档替换重入库已发起: docId={}, name={}", docId, file.getOriginalFilename());
        // replace 端点不消费终态 future（回调层仍需汇聚以统一指标计数路径）
        etlService.process(docId, reindexProgressCallback(doc.getName(), doc.getTenantId(), new CompletableFuture<>()), route);
    }

    /**
     * 重入库状态原子占用：仅 SUCCESS/FAILED 可占用为 REINDEXING；
     * 影响行数 0 = 并发占用或状态不可重入库 → DOC_NOT_READY（409 冲突）。
     *
     * <p><b>内存态同步（2026-08-13 E2E 缺陷修复）</b>：@Modifying 查询只更新 DB
     * （clearAutomatically 已使实体脱管），内存实体仍持占用前旧状态——replace 后续
     * save(doc) 若把陈旧 SUCCESS 回写，ETL 管线误判首次入库（version 不递增、
     * 处理期 REINDEXING 被 PARSING 顶替）。占用成功后同步内存态，后续读写一致。
     */
    private void acquireForReindex(KbDocument doc) {
        int acquired = documentRepository.acquireForReindex(doc.getId(),
            DocumentStatus.REINDEXING, List.of(DocumentStatus.SUCCESS, DocumentStatus.FAILED));
        if (acquired == 0) {
            throw new BusinessException("DOC_NOT_READY",
                "文档当前不可重入库（仅 SUCCESS/FAILED 允许，当前 " + doc.getStatus() + "）: " + doc.getId());
        }
        doc.setStatus(DocumentStatus.REINDEXING);
    }

    /**
     * 重入库进度回调：Redis 双通道 + 终态指标计数 + 终态 future 汇聚（簇③ 4.5）
     * + 语义缓存按文档失效广播（簇③ 5.6 批2）。
     * 异步 ETL 管线的成败观测点在进度回调层（COMPLETED/FAILED 终态帧）。
     *
     * <p><b>失效覆盖面</b>：COMPLETED 终态帧是内容变更提交点——reparse / replace /
     * 索引重建（经 ReindexGateway 委派 {@link #reparse} 同路径）全覆盖，
     * 故重建侧不再单独接线（避免双发）；首次入库亦经此帧（新文档无存量缓存，空转无害）。
     */
    private Consumer<com.enterprise.kb.etl.pipeline.EtlProgress> reindexProgressCallback(
            String docName, String tenantId, CompletableFuture<Boolean> outcome) {
        return progressWriter.andThen(p -> {
            if (p.getStage() == EtlStage.COMPLETED) {
                metrics.recordReindexOutcome(true);
                outcome.complete(true);
                cacheInvalidationPublisher.ifAvailable(publisher -> publisher.publish(tenantId, p.getDocId()));
                // 图谱抽取旁路派发（簇④ 5.1）：与缓存失效同位独立并行——抽取耗时长，
                // 不串行等待；覆盖面同缓存失效（reparse/replace/重建/首次入库），
                // 重建侧不重复接线（委派 reparse 同路径）
                graphExtractionPublisher.ifAvailable(publisher -> publisher.publish(tenantId, p.getDocId()));
                log.info("文档重入库完成: docId={}, name={}", p.getDocId(), docName);
            } else if (p.getStage() == EtlStage.FAILED) {
                metrics.recordReindexOutcome(false);
                outcome.complete(false);
                log.warn("文档重入库失败: docId={}, name={}", p.getDocId(), docName);
            }
        });
    }

    /** 解析路由优先级：显式参数 &gt; 回落值（均经合法性解析，非法/空视为 null） */
    private static ParseRoute firstRoute(String paramRoute, String fallbackRoute) {
        ParseRoute param = parseForcedRoute(paramRoute);
        return param != null ? param : parseForcedRoute(fallbackRoute);
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
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "上传文件超过单文件 50MB 上限");
        }
        if (file.getContentType() != null && !ALLOWED_TYPES.contains(file.getContentType())) {
            throw new BusinessException("FILE_TYPE_UNSUPPORTED",
                "不支持的文件类型: " + file.getContentType() + "，仅支持 PDF/Docx/PPTX/XLSX/MD/TXT/HTML");
        }
    }

    private String extractFileType(String contentType) {
        if (contentType == null) return "UNKNOWN";
        return switch (contentType) {
            case "application/pdf" -> "PDF";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX";
            case "text/markdown" -> "MD";
            case "text/plain" -> "TXT";
            case "text/html" -> "HTML";
            default -> "UNKNOWN";
        };
    }
}
