# 第九章：知识入库 ETL 管道

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31
>
> **v2 修订**：① 解析路由深度链路调整为 API 化解析（DocMind 文档解析大模型版为主；v2.1 按 ECS 资源约束定案，详见 9.1 决策注记）；② 新增 9.4 ES 双写环节（v1 缺失，混合检索的前置依赖）；③ 新增 9.5 Contextual Retrieval 可选增强；④ 管道编排与 Phase 1 已落地实现对齐（`DocumentEtlService`）。
>
> **v2.2 实现期修正（2026-08-03）**：解析支线 2.1-2.3 E2E 实证修正，已回写本章：① DocMind 表格 HTML 需提交时开启 `OutputHtmlTable`（须同开 `LlmEnhancement`），HTML 存放于表格版面块 **`llmResult`** 字段——v2 草图假设的 `html` 键不存在（9.1 实证注记）；② 正文字段实际为 **`markdownContent`**（草图 `markdown` 键不存在，静默回退 `text` 致结构全失）；③ layouts 按页分组输出（每页一个 Document，`page_number` 元数据经切分器下传 → `kb_chunk.page_num`），文本不跨页；④ embedding 单次请求条数硬限制（DashScope ≤20），VectorStore 内部 TokenCountBatchingStrategy 只按 token 预算分批不限条数，ETL 侧固定条数分批（9.3 注记）。

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
| 深度链路 | 阿里云/百度 OCR API（返回 HTML） | **阿里云文档智能 DocMind「文档解析大模型版」**（主选：Markdown + 单元格级表格结构，3000 页/月免费，超出 ¥0.25/页）；qwen3.5-ocr 为低成本备选（约 ¥0.01-0.02/页）；云 OCR 降为扫描件兜底 | 2026 主流本为 MinerU/Docling 自托管，但 Docling 同机部署经复核**不可行**（见下方 v2.1 决策注记：内存峰值 2-4GB vs 本机余量 0-1.5GB、2 核表格解析 30-60 秒/页违反验收线）；DocMind API 与阿里云账号体系统一、零本地算力、零额外运维，中文文档与表格结构还原能力满足需求 |

> **v2.1 资源约束决策注记（2026-07-31 定案，Docling 复核后确认不可行）**：ECS 为 2 核无 GPU 且同时承载 PG/Milvus/ES/Redis/MinIO。针对"Docling 安装要求不高"的复核结论：**装得上 ≠ 跑得动**，五项决定性事实——
> 1. **内存无余量**：全栈在 8GB 机型余量仅 0-1.5GB，而 Docling 解析峰值 2-4GB（官方推荐 8GB），且 PDF 管线与 docling-serve 存在**已知未修复内存泄漏**（docling#2788/#2145，serve 模式 OOM 周期性重启 docling-serve#366）→ 同机部署有拖垮 Milvus/ES 的 OOM 风险；
> 2. **速度违反验收线**：Docling CPU 多核基准 3-6 秒/页，2 核线程争用下表格密集文档约 30-60+ 秒/页，50 页 ≈ 25-50 分钟 vs 验收线「50 页 < 3 分钟」——DEEP 链路恰是表格密集文档；
> 3. **隐私优势不存在**：ETL 链路已将全部 chunk 文本送 DashScope 做 embedding，文档内容早已出域，自托管解析不新增数据保护面；
> 4. **成本近乎免费**：DocMind 大模型版 **3000 页/月免费**、超出 ¥0.25/页；qwen3.5-ocr 备选约 ¥0.01-0.02/页；Phase 2 开发验证量在免费额度内；
> 5. **零运维**：vs Python sidecar（容器 + ~358MB 模型下载 + OOM 看护 + 版本管理），Phase 2 仅需一个 Java HTTP 客户端。
>
> **权衡记录**：代价为按页 API 费用（免费额度内）与外网依赖（ETL 异步链路延迟不敏感，且与 embedding/LLM 既有外网依赖一致，可接受）。**实施前置**：DocMind 使用**阿里云 AccessKey（RAM 鉴权）**而非 DashScope API Key，需用户侧提供；异步 API（提交 → 轮询）的轮询与超时降级逻辑在 `DocMindParsingClient` 内实现。
>
> **Docling 重估触发条件**（备查）：① ECS 扩容至 16GB+ 或置独立解析节点；② 出现数据闭境合规要求；③ 月解析量超十万页且 API 成本显著。`ParsingServiceClient` 设计为**可插拔后端**（`DocMindParsingClient` / `QwenVlOcrParsingClient` / 可选 `DoclingClient` / `OcrApiClient` 兜底），条件满足时可平滑接入，架构不受影响。

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
 * - 密度正常但表格/图片密集     → DEEP（DocMind 解析 API）
 */
public class SmartParsingRouter implements DocumentReader {

    private static final double TEXT_DENSITY_THRESHOLD = 0.05;  // 字符数/页面面积比
    private static final int PROBE_PAGES = 3;                   // 探测页数
    private static final double COMPLEX_TABLE_RATIO = 0.3;      // 表格区域占比阈值

    private final Resource resource;
    private final TextDensityAnalyzer densityAnalyzer;   // PDFBox 文本提取 + 启发式
    private final ParsingServiceClient parsingService;   // 解析 API 客户端（可插拔后端：DocMind / qwen3.5-ocr / Docling）
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

    /** 深度链路：DocMind 返回结构化结果（Markdown 正文 + 表格 HTML 块 + 图片描述） */
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

> **v2.2 实证注记（2026-08-03，DocMind 文档解析大模型版真实接入后回写）**：
> 1. **表格 HTML 的获取方式与 v2 草图不同**：需提交时显式开启 `OutputHtmlTable=true`（官方约束须同时开启 `LlmEnhancement`），表格 HTML 存放在表格版面块的 **`llmResult`** 字段（实测以 ```` ```html ```` 代码围栏包裹，提取时剥离）；草图假设的 `html` 键在该 API 不存在。未开启时表格仅以管道符 Markdown 形态返回，`<table>` 保护失效。
> 2. **正文字段名为 `markdownContent`**（非草图的 `markdown`）；`text` 为无结构纯文本回退。防御式提取链：表格 `llmResult`→`html`→`markdownContent`→`text`，正文 `markdownContent`→`text`。
> 3. **图片块**：`figure`/`image` 版面块仅计数不进正文（IMAGE Chunk 的 vision 摘要见 9.2/2.4，默认关闭）；表格密集文档实测 9 页 → 35 chunks（16 TABLE 完整保护 + 19 TEXT，页级切分文本不跨页）。
> 4. **页码下传**：layouts 携带 `pageNum`（0 起），解析结果按页分组为每页一个 Document，`page_number` 元数据经 HtmlProtectingSplitter 原样下传，落库 `kb_chunk.page_num`（NATIVE 路由 Tika 无页级信息，page_num 为 null 属数据源限制）。
> 5. **路由决策修正**：草图的「表格区域占比」探测需版面分析引擎、解析前不可得——DEEP 路由改经配置开关（`kb.parsing.deep-by-default`）与上传参数（`parseRoute=DEEP`）显式触发，密度探测仅承担扫描件 OCR 识别；路由结构保留草图形态，未来版面探针可插入 `decide()`。

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
        .withMaxNumChunks(10000)   // 切片数上限（官方默认）：非切片大小！超限后尾部剩余并入单个超大块，触发 embedding 输入超长拒绝
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
EMBEDDING      VectorStore.add() 批量向量化（pgvector / Milvus，内部自动 embed；
               ★v2.2：embedding 服务商有单次请求条数硬限制（DashScope ≤20），
               VectorStore 内部 TokenCountBatchingStrategy 只按 token 预算分批、不限条数，
               ETL 侧须按固定条数（10/批）分批调用，小 chunk 密集文档否则触发 400）
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
