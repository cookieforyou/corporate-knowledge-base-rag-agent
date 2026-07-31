# 第九章：知识入库 ETL 管道

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31
>
> **v2 修订**：① 解析路由深度链路从云 OCR API 调整为 MinerU/Docling 解析服务；② 新增 9.4 ES 双写环节（v1 缺失，混合检索的前置依赖）；③ 新增 9.5 Contextual Retrieval 可选增强；④ 管道编排与 Phase 1 已落地实现对齐（`DocumentEtlService`）。

---

## 9.0 ETL 异步执行器配置（Phase 1 已实现）

```java
package com.enterprise.kb.etl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
@EnableAsync
public class EtlExecutorConfig {

    /**
     * ETL 专用虚拟线程执行器 —— 文档解析和向量化是 I/O 密集型任务，
     * 虚拟线程是最佳选择，避免传统线程池耗尽 Web 线程
     */
    @Bean("etlExecutor")
    public Executor etlExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
```

---

## 9.1 双链路解析路由（v2 修订）

### 设计原则

2026 年企业文档解析的主流形态是**混合路由**：Tika 管广度（纯文本电子文档，便宜快速），深度解析器管复杂度（表格密集/扫描/复杂版式）。v1 设计的文本密度探测 + 动态路由框架**保留**，深度链路**调整**：

| 链路 | v1 设计 | v2 修订 | 理由 |
|---|---|---|---|
| 原生链路 | Tika | Tika（不变） | 电子版本文档的最优解 |
| 深度链路 | 阿里云/百度 OCR API（返回 HTML） | **MinerU / Docling 自托管解析服务**（HTTP sidecar），云 OCR 降为扫描件兜底 | MinerU 布局检测 97.5 mAP、表格结构还原强、对中文文档友好；Docling 生态集成好。两者均开源可自托管，输出 Markdown/HTML+JSON 结构化结果，质量上限高于通用云 OCR |

> MinerU/Docling 均为 Python 实现，以独立 HTTP 服务形态部署（Phase 2.2），kb-infrastructure 侧提供 `ParsingServiceClient` 适配层。

### 路由决策逻辑

```java
package com.enterprise.kb.etl.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 智能解析路由器：基于文本密度探测动态选择解析链路
 *
 * 决策树：
 * - 文本密度 > 阈值 且 无复杂表格 → NATIVE（Tika）
 * - 文本密度 < 阈值（疑似扫描件） → OCR 兜底链路（云 OCR API）
 * - 密度正常但表格/图片密集     → DEEP（MinerU/Docling 解析服务）
 */
public class SmartParsingRouter implements DocumentReader {

    private static final double TEXT_DENSITY_THRESHOLD = 0.05;  // 字符数/页面面积比
    private static final int PROBE_PAGES = 3;                   // 探测页数
    private static final double COMPLEX_TABLE_RATIO = 0.3;      // 表格区域占比阈值

    private final Resource resource;
    private final TextDensityAnalyzer densityAnalyzer;   // PDFBox 文本提取 + 启发式
    private final ParsingServiceClient parsingService;   // MinerU/Docling HTTP 客户端
    private final OcrApiClient ocrApiClient;             // 云 OCR 兜底
    private final Map<String, Object> customMetadata;

    @Override
    public List<Document> get() {
        var probe = densityAnalyzer.analyze(resource, PROBE_PAGES);

        List<Document> docs;
        if (probe.textDensity() < TEXT_DENSITY_THRESHOLD) {
            docs = parseViaOcr(resource);            // 扫描件：OCR 兜底
            mark(docs, ParseRoute.OCR);
        } else if (probe.tableRatio() > COMPLEX_TABLE_RATIO) {
            docs = parseViaService(resource);        // 复杂表格：深度解析服务
            mark(docs, ParseRoute.DEEP);
        } else {
            docs = parseViaTika(resource);           // 常规电子文档
            mark(docs, ParseRoute.NATIVE);
        }
        docs.forEach(d -> d.getMetadata().putAll(customMetadata));
        return docs;
    }

    private List<Document> parseViaTika(Resource resource) {
        return new TikaDocumentReader(resource).get();
    }

    /** 深度链路：MinerU/Docling 服务返回结构化结果（Markdown 正文 + 表格 HTML 块 + 图片描述） */
    private List<Document> parseViaService(Resource resource) {
        ParsingResult result = parsingService.parse(resource);
        Document doc = new Document(result.markdownWithHtmlTables());
        doc.getMetadata().put("table_count", result.tableCount());
        doc.getMetadata().put("image_count", result.imageCount());
        return List.of(doc);
    }

    private List<Document> parseViaOcr(Resource resource) {
        OcrResult result = ocrApiClient.parseToHtml(resource);
        Document doc = new Document(result.getHtmlContent());
        doc.getMetadata().put("table_count", result.getTableCount());
        doc.getMetadata().put("image_count", result.getImageCount());
        return List.of(doc);
    }

    private void mark(List<Document> docs, ParseRoute route) {
        docs.forEach(d -> d.getMetadata().put("parse_route", route.name()));
    }

    // Builder 省略
}
```

`ParseRoute` 枚举（kb-domain，v2 扩充）：`NATIVE` / `DEEP` / `OCR`。`kb_document.parse_route` 落库该值，供运维统计各链路占比与质量回溯。

---

## 9.2 HtmlProtectingSplitter（保护式切分）

表格/图片作为一等公民保护，与 2026 年"结构感知切分"标准对齐：

- `<table>` 块 → 独立 Chunk（`chunk_type=TABLE`），原文 HTML 存入 `original_content`；
- `<img>` 块 → 独立 Chunk（`chunk_type=IMAGE`）。注意：`<img>` 的 outerHtml 仅含 URL/alt，对检索与 LLM 均不可用——**图片 Chunk 走可选 vision 摘要**：调用多模态模型生成 ~100 字图片描述写入 `content`（参与 embedding/检索），`original_html` 存 `<img>` 标签供前端回显原图。该环节与 9.5 Contextual 增强共用开关与执行机制（Phase 2.4 覆盖）；
- 纯文本 → `TokenTextSplitter` 常规切分（800/200，与 Phase 1 参数一致）；
- 保护块前后的短文本 → 并入最近文本 Chunk，避免孤立碎片；
- 小表格（< 30 字符）退化为纯文本，避免噪声 Chunk。

```java
package com.enterprise.kb.etl.transformer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.*;

/**
 * HTML 结构保护式切分器
 *
 * <p>无保护标签的文档走快速路径（直接 TokenTextSplitter，Phase 1 行为）；
 * 含 table/img 的文档走 JSoup AST 解析 + 保护块提取。</p>
 */
public class HtmlProtectingSplitter implements DocumentTransformer {

    private static final List<String> PROTECTED_TAGS = List.of("table", "img");
    private static final int MIN_TABLE_CHARS = 30;

    private final TokenTextSplitter textSplitter = TokenTextSplitter.builder()
        .withChunkSize(800)
        .withMinChunkSizeChars(200)
        .withMinChunkLengthToEmbed(10)
        .withMaxNumChunks(5)
        .withKeepSeparator(true)
        .build();

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (!containsProtectedTags(text)) {
                result.addAll(textSplitter.apply(List.of(doc)));   // 快速路径
                continue;
            }
            result.addAll(splitWithProtection(doc));
        }
        return result;
    }

    private List<Document> splitWithProtection(Document doc) {
        List<Document> result = new ArrayList<>();
        List<ContentBlock> blocks = extractBlocks(Jsoup.parse(doc.getText()).body());
        StringBuilder textBuffer = new StringBuilder();

        for (ContentBlock block : blocks) {
            switch (block.type()) {
                case TEXT -> textBuffer.append(block.content()).append("\n");
                case TABLE, IMAGE -> {
                    // 先冲刷累积文本
                    if (!textBuffer.isEmpty()) {
                        result.addAll(textSplitter.apply(
                            List.of(doc.mutate().text(textBuffer.toString()).build())));
                        textBuffer.setLength(0);
                    }
                    // 保护块独立成 Chunk
                    Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                    meta.put("chunk_type", block.type().name());
                    meta.put("original_html", block.content());
                    result.add(new Document(block.content(), meta));
                }
            }
        }
        if (!textBuffer.isEmpty()) {
            result.addAll(textSplitter.apply(
                List.of(doc.mutate().text(textBuffer.toString()).build())));
        }
        return result;
    }

    private List<ContentBlock> extractBlocks(Element body) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (Element child : body.children()) {
            switch (child.tagName()) {
                case "table" -> {
                    String html = child.outerHtml();
                    blocks.add(html.length() >= MIN_TABLE_CHARS
                        ? new ContentBlock(ContentType.TABLE, html)
                        : new ContentBlock(ContentType.TEXT, child.text()));
                }
                case "img" -> blocks.add(
                    new ContentBlock(ContentType.IMAGE, child.outerHtml()));
                default -> blocks.add(
                    new ContentBlock(ContentType.TEXT, child.text()));
            }
        }
        return blocks;
    }

    private boolean containsProtectedTags(String html) {
        String lower = html.toLowerCase();
        return PROTECTED_TAGS.stream().anyMatch(tag -> lower.contains("<" + tag));
    }

    private enum ContentType { TEXT, TABLE, IMAGE }
    private record ContentBlock(ContentType type, String content) {}
}
```

> **切分策略演进注记**：固定 Token 切分在 2026 年已是基线水平。语义切分（SemanticChunker，基于 embedding 相似度的语义断句）是 Phase 5+ 的演进方向，本阶段以「结构感知保护 + 可选 Contextual Retrieval（9.5）」为边界；本切分器的结构保护能力与后续语义切分不冲突，是其载体。

---

## 9.3 管道编排（与 Phase 1 实现对齐）

Phase 1 已落地 `DocumentEtlService`（MinIO 拉取 → Tika 解析 → Token 切分 → `kb_chunk` 落库 → `VectorStore.add()`）。Phase 2 在原位扩展为完整管道：

```
READING        SmartParsingRouter 密度探测 + 链路路由（9.1）
   ↓
TRANSFORMING   HtmlProtectingSplitter 保护式切分（9.2）
   ↓
PERSISTING     kb_chunk 批量落库（Document.id = chunkId = vectorId，全链路融合键）
   ↓
EMBEDDING      VectorStore.add() 批量向量化（pgvector / Milvus，内部自动 embed）
   ↓
INDEXING ★新增  ES kb_chunks 索引双写（9.4）
   ↓
COMPLETED      kb_document 状态回写（chunk_count / table_count / parse_route）
```

**关键不变量**（混合检索依赖，禁止破坏）：

1. `Document.id = KbChunk.id = KbChunk.vectorId = ES _id = chunk_id`——RRF 融合键单一来源；
2. 向量元数据必含 `chunk_id / doc_id / tenant_id / chunk_type / page_num`（pgvector 的 metadata JSONB、Milvus 的标量字段同源）；
3. 任一路写入失败不回滚其他路，但 `kb_document.status = FAILED` + `error_message` 记录失败阶段，支持按文档重试（幂等：重试前按 doc_id 清理旧 chunk/向量/ES 文档）。

```java
// Stage 4 之后插入 Stage 5（DocumentEtlService.process 内）
progressCallback.accept(new EtlProgress(docId, EtlStage.INDEXING));
esIndexWriter.indexChunks(doc, entities);   // 9.4
```

`EtlStage` 枚举扩充：`READING, TRANSFORMING, PERSISTING, EMBEDDING, INDEXING, COMPLETED, FAILED`。

---

## 9.4 ES 索引双写（v2 新增）

v1 设计了 ES 检索却缺失写入环节——本章补齐。`EsIndexWriter` 将 Chunk 同步写入 `kb_chunks` 索引（mapping 见第十章 10.3）：

```java
package com.enterprise.kb.etl.writer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES 双写器 —— chunk 与向量库同批写入，chunk_id 为文档 _id（幂等）
 */
@Component
public class EsIndexWriter {

    private final ElasticsearchClient esClient;
    private static final String INDEX = "kb_chunks";

    public void indexChunks(KbDocument doc, List<KbChunk> entities) {
        var ops = entities.stream()
            .map(e -> EsChunkDoc.builder()
                .chunkId(e.getId())
                .docId(doc.getId())
                .tenantId(doc.getTenantId())
                .content(e.getContent())
                .chunkType(e.getChunkType().name())
                .fileName(doc.getOriginalName())
                .pageNum(e.getPageNum())
                .isDeleted(false)
                .createdAt(e.getCreatedAt())
                .build())
            .map(d -> new BulkOperation.Builder()
                .index(idx -> idx.index(INDEX).id(d.getChunkId()).document(d))
                .build())
            .toList();

        var response = esClient.bulk(b -> b.operations(ops).refresh(true));
        if (response.errors()) {
            // 部分失败：记录失败 chunk_id 到 kb_document.error_message，不阻断主流程
            log.error("ES 双写部分失败: docId={}, items={}", doc.getId(),
                response.items().stream().filter(i -> i.error() != null).count());
        }
    }

    /** 软删除同步（Chunk 编辑/删除时，第十四章运维 API 调用） */
    public void markDeleted(String chunkId) {
        esClient.update(u -> u.index(INDEX).id(chunkId)
            .doc(Map.of("is_deleted", true)), EsChunkDoc.class);
    }

    /** 文档物理删除时级联清理（第十四章） */
    public void deleteByDocId(String docId) {
        esClient.deleteByQuery(d -> d.index(INDEX)
            .query(q -> q.term(t -> t.field("doc_id").value(docId))));
    }
}
```

**一致性模型**：ES 是向量库的**从属副本**——PG `kb_chunk` 为唯一事实源，ES 与向量库均可从 PG 全量重建（第十四章索引重建 API）。双写失败不阻断 ETL，由重建任务兜底。

---

## 9.5 Contextual Retrieval 增强（v2 新增，默认关闭）

Anthropic Contextual Retrieval（2024 提出，2026 已被 AWS Bedrock 等原生集成）：embedding 前为每个 Chunk 生成一段"文档级上下文摘要"前缀，将 Chunk 放回文档语境，显著提升检索准确率（官方报告检索失败率降 67%）。

```java
package com.enterprise.kb.etl.transformer;

/**
 * 上下文增强器 —— 可选环节，置于切分之后、向量化之前
 *
 * <p>对每个 Chunk 调用轻量 LLM：输入「文档前 N 字符概要 + Chunk 原文」，
 * 输出 50-100 字上下文说明，拼接到 Chunk 文本前再参与 embedding 与 ES 索引。</p>
 *
 * <p>成本控制：
 * - 默认关闭（kb.etl.contextual.enabled=false），按知识库/文档类型选择性开启；
 * - Prompt Caching 摊薄文档概要部分的 token 成本（同一文档的所有 Chunk 共享缓存前缀）；
 * - 使用经济模型（deepseek-v4-flash）生成上下文。</p>
 */
@Component
@ConditionalOnProperty(prefix = "kb.etl.contextual", name = "enabled", havingValue = "true")
public class ContextualEnrichmentTransformer implements DocumentTransformer {

    private static final String CONTEXT_PROMPT = """
        <document>
        %s
        </document>

        请用 50-100 字说明下面这个片段在文档中的位置与作用（涉及什么主题、与上下文的关系），
        只输出说明文本：
        <chunk>
        %s
        </chunk>
        """;

    // apply(): 对每个 chunk 生成 context 前缀
    // enriched = "【上下文】" + context + "\n" + originalContent
    // content 字段存 enriched（参与 embedding/检索），original_content 存原文（展示用）
}
```

**与数据模型的契合**：`kb_chunk.original_content` 字段（schema 已预留）存原文，`content` 存增强后文本——前端 Chunk 观测台展示原文，检索走增强文本，两者天然分离。

---

## 9.6 异步与进度推送

`@Async("etlExecutor")` + `EtlProgress` 回调（Phase 1 已实现，当前仅日志输出）。Phase 2.13 扩展：

- 进度写入 Redis（`etl:progress:{docId}` Hash，TTL 24h，键规划见第七章 7.5）；
- WebSocket 端点 `/ws/etl/progress` 向前端推送 `EtlProgress`（stage/chunkCount/processedChunks/percentage）；
- 前端文档上传组件订阅进度条，完成后自动刷新文档列表状态。
