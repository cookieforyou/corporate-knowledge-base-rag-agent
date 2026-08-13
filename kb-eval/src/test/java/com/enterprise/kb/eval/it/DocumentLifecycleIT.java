package com.enterprise.kb.eval.it;

import com.enterprise.kb.domain.enums.DocumentStatus;
import com.enterprise.kb.domain.enums.ParseRoute;
import com.enterprise.kb.domain.model.KbChunk;
import com.enterprise.kb.domain.model.KbDocument;
import com.enterprise.kb.domain.repository.KbChunkRepository;
import com.enterprise.kb.domain.repository.KbDocumentRepository;
import com.enterprise.kb.etl.service.ChunkCleanupService;
import com.enterprise.kb.etl.service.DocumentEtlService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档生命周期集成（簇⑥ D3 × C1 回归保险）：真实 PG + pgvector + MinIO 上跑
 * {@link DocumentEtlService} 全管线（NATIVE Tika 解析、桩向量化、ES 降级）——
 *
 * <ul>
 *   <li>首次入库：chunk 落库 + 向量同 ID 双写（融合键不变量）</li>
 *   <li>重解析（蓝绿）：确定性 ID 逐位复现、created_at 保留（updatable=false 实证）、
 *       version 递增</li>
 *   <li>内容替换（蓝绿 diff）：旧有新无 chunk 三库清理、存活 chunk 原 ID 保留</li>
 *   <li>软删：is_deleted 置位 + 向量物理删</li>
 * </ul>
 */
class DocumentLifecycleIT extends AbstractAdvisorChainIT {

    private static final String TENANT = "T-LIFE";

    @Autowired private DocumentEtlService etlService;
    @Autowired private KbDocumentRepository documentRepository;
    @Autowired private KbChunkRepository chunkRepository;
    @Autowired private ChunkCleanupService chunkCleanupService;
    @Autowired private MinioClient minioClient;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Value("${minio.bucket}")
    private String bucket;

    @BeforeEach
    void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    // ── 入库 helper ──

    private String ingest(String name, String content) throws Exception {
        String docId = UUID.randomUUID().toString();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String ossPath = docId + "/" + name;
        minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(ossPath)
            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
            .contentType("text/markdown").build());

        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setTenantId(TENANT);
        doc.setName(name);
        doc.setOriginalName(name);
        doc.setType("MD");
        doc.setSize((long) bytes.length);
        doc.setOssPath(ossPath);
        doc.setStatus(DocumentStatus.UPLOADING);
        doc.setVersion(1);
        documentRepository.save(doc);

        etlService.process(docId, p -> { }, ParseRoute.NATIVE);
        awaitTerminal(docId);
        return docId;
    }

    private KbDocument awaitTerminal(String docId) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
            .until(() -> documentRepository.findById(docId)
                .map(d -> d.getStatus() == DocumentStatus.SUCCESS || d.getStatus() == DocumentStatus.FAILED)
                .orElse(false));
        KbDocument doc = documentRepository.findById(docId).orElseThrow();
        assertThat(doc.getStatus())
            .as("ETL 应成功，errorMessage=%s", doc.getErrorMessage())
            .isEqualTo(DocumentStatus.SUCCESS);
        return doc;
    }

    private long embeddingCountOf(String chunkId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM kb_embeddings WHERE id = ?", Long.class, chunkId);
        return count == null ? 0 : count;
    }

    /** 构造 ~600 字节的主题段落（10 条条款），多段落拼接使切分器产出多 chunk */
    private static String section(String topic, int index) {
        StringBuilder sb = new StringBuilder("## " + topic + "\n\n");
        for (int i = 0; i < 10; i++) {
            sb.append(topic).append("条款").append(index).append('-').append(i)
                .append("：本条规定").append(topic).append("的执行细则与具体要求。\n");
        }
        return sb.toString();
    }

    // ── 用例 ──

    @Test
    void firstIngest_persistsChunksAndVectors() throws Exception {
        String docId = ingest("首次入库.md",
            section("年假管理", 1) + "\n" + section("报销流程", 2) + "\n" + section("考勤制度", 3));

        KbDocument doc = documentRepository.findById(docId).orElseThrow();
        assertThat(doc.getChunkCount()).isPositive();
        assertThat(doc.getVersion()).isEqualTo(1);

        List<KbChunk> chunks = chunkRepository.findByDocIdOrderByChunkIndex(docId);
        assertThat(chunks).hasSize(doc.getChunkCount());
        for (KbChunk chunk : chunks) {
            // 融合键不变量：chunk id = vector_id = kb_embeddings 行
            assertThat(chunk.getVectorId()).isEqualTo(chunk.getId());
            assertThat(embeddingCountOf(chunk.getId())).isEqualTo(1);
        }
    }

    @Test
    void reparse_idempotent_sameIds_createdAtPreserved_versionBumped() throws Exception {
        String content = section("年假管理", 1) + "\n" + section("报销流程", 2);
        String docId = ingest("重解析.md", content);

        Map<String, LocalDateTime> before = chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().collect(Collectors.toMap(KbChunk::getId, KbChunk::getCreatedAt));

        int acquired = documentRepository.acquireForReindex(docId,
            DocumentStatus.REINDEXING, List.of(DocumentStatus.SUCCESS, DocumentStatus.FAILED));
        assertThat(acquired).isEqualTo(1);

        etlService.process(docId, p -> { }, ParseRoute.NATIVE);
        KbDocument doc = awaitTerminal(docId);

        assertThat(doc.getVersion()).isEqualTo(2);
        Map<String, LocalDateTime> after = chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().collect(Collectors.toMap(KbChunk::getId, KbChunk::getCreatedAt));
        // 确定性 ID 逐位复现 + created_at 保留（updatable=false 集成实证）
        assertThat(after.keySet()).isEqualTo(before.keySet());
        after.forEach((id, createdAt) ->
            assertThat(createdAt).as("chunk %s created_at 应保留原值", id).isEqualTo(before.get(id)));
    }

    @Test
    void replace_diffCleanupRemovesStaleChunks() throws Exception {
        String docId = ingest("替换.md", section("年假管理", 1) + "\n" + section("报销流程", 2));
        Set<String> oldIds = new HashSet<>(chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().map(KbChunk::getId).toList());
        assertThat(oldIds).isNotEmpty();

        // 替换原件：第二段整体换词（报销流程 → 差旅标准）→ chunk ID 集合漂移
        KbDocument before = documentRepository.findById(docId).orElseThrow();
        byte[] bytes = (section("年假管理", 1) + "\n" + section("差旅标准", 9))
            .getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(before.getOssPath())
            .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
            .contentType("text/markdown").build());

        int acquired = documentRepository.acquireForReindex(docId,
            DocumentStatus.REINDEXING, List.of(DocumentStatus.SUCCESS, DocumentStatus.FAILED));
        assertThat(acquired).isEqualTo(1);

        etlService.process(docId, p -> { }, ParseRoute.NATIVE);
        KbDocument doc = awaitTerminal(docId);

        assertThat(doc.getVersion()).isEqualTo(2);
        Set<String> newIds = new HashSet<>(chunkRepository.findByDocIdOrderByChunkIndex(docId)
            .stream().map(KbChunk::getId).toList());
        assertThat(newIds).isNotEmpty();

        // 蓝绿 diff：旧有新无 chunk 在 PG 与向量库均被清理
        Set<String> stale = new HashSet<>(oldIds);
        stale.removeAll(newIds);
        for (String staleId : stale) {
            assertThat(chunkRepository.findById(staleId)).isEmpty();
            assertThat(embeddingCountOf(staleId)).isZero();
        }
        // 存活 chunk 原 ID 保留且向量随行
        for (String surviving : newIds) {
            assertThat(embeddingCountOf(surviving)).isEqualTo(1);
        }
    }

    @Test
    void softDelete_flagsChunkAndRemovesVector() throws Exception {
        String docId = ingest("软删.md", section("年假管理", 1) + "\n" + section("报销流程", 2));
        KbChunk victim = chunkRepository.findByDocIdOrderByChunkIndex(docId).get(0);

        chunkCleanupService.softDelete(victim.getId());

        KbChunk reloaded = chunkRepository.findById(victim.getId()).orElseThrow();
        assertThat(reloaded.getIsDeleted()).isTrue();
        // 向量库无软删形态：物理删除（读侧双路 is_deleted 过滤为兜底）
        assertThat(embeddingCountOf(victim.getId())).isZero();
    }
}
