# 第九章：知识入库 ETL 管道

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-08-13（v2.27 簇⑥ C1 收尾）
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
> **权衡记录**：代价为按页 API 费用（免费额度内）与外网依赖（ETL 异步链路延迟不敏感，且与 embedding/LLM 既有外网依赖一致，可接受）。**实施前置**：DocMind 使用**阿里云 AccessKey（RAM 鉴权）**而非 DashScope API Key（Phase 2 已提供并 E2E 通过；ECS 生产 .env 接线启用见用户侧待执行项清单 D1）；异步 API（提交 → 轮询）的轮询与超时降级逻辑在 `DocMindParsingClient` 内实现。
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

> **v2.21 修正（2026-08-12，簇④ A4 heading 路径元数据）**：切分时维护六级标题栈——Markdown `#{1,6} ` 行与 HTML `<h1>..<h6>` 双形态识别，每个 chunk 注入 `heading_path` 元数据（「L1 > L2 > …」）。三条实现纪律：
> 1. **标题变更即冲刷缓冲**：chunk 边界与章节边界对齐（topic-aligned），标题文字保留在新 chunk 正文首部（BM25/向量化可检索）；
> 2. **三路分发**：无保护标签且无标题 → 原快速路径零变化；仅标题无保护标签 → 纯行扫描（**不经 JSoup**——代码片段尖括号 `List<String>` 会被 JSoup 解析为未知标签丢文本）；有保护标签 → JSoup AST 路径（尖括号风险为 v2 既有边界，不扩大）；
> 3. **三存储面落地**：`kb_chunk.metadata` JSONB 的 heading_path 键 + 向量库元数据（缺省不写键，元数据禁 null）+ ES `heading_path` 字段（新建索引走 mapping ik 分词，存量索引 dynamic mapping 自动映射，完全对齐随 Phase 4.6 索引重建窗口）。载体经 `KbChunk.headingPath` @Transient 字段流转（免 ECS ALTER）。展示与检索两用；BM25 查询侧消费（multi_match 纳入 heading_path）待 contextual A/B 决策后评估，避免双重变量污染基线。

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
CLEANUP ★v2.25  蓝绿 diff 清理——三库物理删除「旧有新无」chunk（9.3 v2.25；
               首次入库旧集为空即空操作）
   ↓
COMPLETED      kb_document 状态回写（chunk_count / table_count / parse_route；
               重入库入口成功时 version +1）
```

**关键不变量**（混合检索依赖，禁止破坏）：

1. `Document.id = KbChunk.id = KbChunk.vectorId = ES _id = chunk_id`——RRF 融合键单一来源；
2. 向量元数据必含 `chunk_id / doc_id / tenant_id / chunk_type / page_num`（pgvector 的 metadata JSONB、Milvus 的标量字段同源）；
3. 任一路写入失败不回滚其他路，但 `kb_document.status = FAILED` + `error_message` 记录失败阶段，支持按文档重试（幂等：重试前按 doc_id 清理旧 chunk/向量/ES 文档）。

> **v2.22 修正（2026-08-12，簇④ A4 检索锚点修复）——chunk ID 确定性化**：
> 不变量 1 的 `chunkId` 取值由**随机 UUID 改为确定性 nameUUID**：
> `chunkId = UUID.nameUUIDFromBytes((文档名 + "#" + 序号 + "#" + 增强前原文).getBytes(UTF-8))`。
>
> **动机**：随机 UUID 方案下全量重入库（删后重传）令所有 chunk 换新 ID，kb-eval
> Golden Dataset 的 `expectedChunkIds` 整体失配——2026-08-12 a4-heading-only 复跑
> 检索三指标全 0.000（生成侧 F 反涨至 4.750 证明检索本身正常，纯度量尺断）。
>
> **确定性语义**：同一文档重入库（解析/切分产物逐位不变）→ ID 逐位复现 →
> Golden 标注跨重入库不失效，contextual A/B 两臂（各一次重入库）天然可比。
> **baseText 必须取增强前原文**（`original_text` 元数据）：contextual 开启后
> content 带「【上下文】」前缀，若参与散列则 A/B 两臂 ID 分叉，A/B 不可比。
>
> **已知边界**：ID 稳定性以「解析产物逐位复现」为前提；深度链路 DocMind 的
> LLM 增强表格 HTML 若跨调用漂移 → chunk 内容变 → ID 变 → Golden 失配，
> 届时由 16 章文档级兜底指标（file_name 匹配）定位。解析产物漂移治理属 C1 议题。

```java
// Stage 4 之后插入 Stage 5（DocumentEtlService.process 内）
progressCallback.accept(new EtlProgress(docId, EtlStage.INDEXING));
esIndexWriter.indexChunks(doc, entities);   // 9.4
```

`EtlStage` 枚举扩充：`READING, TRANSFORMING, PERSISTING, EMBEDDING, INDEXING, CLEANUP, COMPLETED, FAILED`。

> **v2.25 修正（2026-08-13，簇⑥ C1 增量重入库）——蓝绿管线与增量 API**：
> ① **管线统一为「全量写入 → diff 清理」**：确定性 chunk ID（v2.22）令不变 chunk
> 三库同 ID 幂等覆写（PG merge / 向量 upsert / ES 同 `_id` 覆盖），故写入前捕获
> 旧 chunkId 快照，INDEXING 后计算 diff = 旧有新无 → 经 `ChunkCleanupService.physicalDelete`
> 三库精确清理（ES 走 `deleteByChunkIds` bulk 删，**不可用 deleteByDocId**——会误删
> 同文档存活 chunk）。首次入库旧集为空即空操作，两路径归一无分叉。
> ② **失败语义**：清理前失败 = 新旧混合仍可检索（旧数据大体保留），重试幂等收敛；
> 清理自身失败上抛 FAILED（残留旧 chunk 可见 = 潜在过期答案，须重试收敛）。
> ③ **增量 API**：`POST /documents/{id}/reparse`（MinIO 原件重走 ETL，路由缺省复现
> 原始路由）/ `POST /documents/{id}/replace`（新文件覆盖原件，路由缺省自动决策）；
> 状态守卫经 DB 级原子占用 `UPDATE kb_document SET status='REINDEXING' WHERE id=?
> AND status IN ('SUCCESS','FAILED')`（影响行数 0 → DOC_NOT_READY 409，零 Redis 依赖）；
> 处理期保持 REINDEXING 状态（不回写 PARSING），成功 version+1 + 清空 error_message。
> ④ **kb_document.version 列**（07 章同步）：首次入库 1、每次重入库成功 +1——
> [ref-N] 引用经 docId 定位文档不因重入库碎裂，版本号为运维审计追溯维度。

> **v2.26 修正（2026-08-13，簇⑥ C1 E2E 缺陷修复）——占用态回写与 created_at 覆写**：
> E2E 实测发现两缺陷（reparse 正常 version+1；replace version 不递增、两文档全部
> chunk created_at 刷新为入库时刻）：
> ① **replace 占用态回写**：`acquireForReindex` 的 @Modifying 查询只更新 DB
> （clearAutomatically 已使实体脱管），内存实体仍持占用前旧状态——replace 后续
> `save(doc)` 把陈旧 SUCCESS 回写 → ETL 重读误判首次入库（version 不递增、处理期
> REINDEXING 被 PARSING 顶替；守卫不失效——PARSING 同样不在可占用集）。
> 修复 = 占用成功后同步内存态 `doc.setStatus(REINDEXING)`。
> ② **chunk created_at merge 覆写**：persistChunks 手工 `createdAt=now`，蓝绿同 ID
> merge 时 @PreUpdate 只刷 updatedAt，手工值随 UPDATE 覆盖原创建时间。
> 修复 = `KbChunk.createdAt` @Column(updatable=false) 排除出 UPDATE（INSERT 仍写入，
> merge 保留原值）——created_at 恢复「首次入库时刻」语义，且重入库后可经 created_at
> 区分「覆写存活 vs 新增」chunk（观测性恢复）。
> ③ **E2E 核验数据**：reparse（内容不变）后 7 chunk 确定性 ID 逐位复现——本地按
> nameUUID(文档名#序号#增强前原文) 重算 7/7 全匹配，蓝绿同 ID 幂等覆写实证；
> replace（一行规格变更）后 8 chunk 同式自洽。
> **复验通过（2026-08-13 同日）**：replace version 递增 + 处理期「重入库中」展示、
> reparse 未变 chunk created_at 保留原值、处理中再发重入库 409、reindex 指标计数正确。

> **v2.27 修正（2026-08-13，簇⑥ C1 收尾）——删除处理期守卫**：
> 重入库窗口令「处理期删除」成为现实误操作面——级联清理与在途 ETL 竞态（孤儿写回：
> ETL 后续 persistChunks/状态回写作用于已删文档），误删正重入库的文档更直接损失可用性。
> ① **后端守卫**：`DocumentService.delete` 租户校验后加状态守卫——处理期三态
> （UPLOADING/PARSING/REINDEXING）拒删 → DOC_NOT_READY(409，与重入库守卫同错误码族)；
> SUCCESS/FAILED 放行（FAILED 删除是正当清理路径）。状态集判定经
> `DocumentStatus.isProcessing()`（domain 枚举单一来源）。
> ② **前端联动**：Documents.vue 删除按钮 `:disabled` 同状态集（isLiveDocStatus），
> 与重解析/替换的 canReindex 同构；后端守卫为兜底（防列表状态滞后/绕过前端直调）。
> ③ **语义边界**：守卫是误操作防御而非并发控制——守卫读与级联删除间存在 TOCTOU
> 窗口，最坏并发 ETL 重读时 DOC_NOT_FOUND 即败（process() try 块之外，无 FAILED
> 进度帧）；单机工作台规模不为删除引入原子占用。

---

## 9.4 ES 索引双写（v2 新增）

v1 设计了 ES 检索却缺失写入环节——本章补齐。`EsIndexWriter` 将 Chunk 同步写入 `kb_chunks` 索引（mapping 见第十章 10.3）。

> **v2.19 修正（2026-08-11，簇③ D2）**：批量写入刷新策略 `refresh(true)` → `Refresh.WaitFor`——语义仍为「返回即可检索」（请求挂起至下一次刷新周期完成），但避免大文档 ETL 尾部每批强制全索引刷新的长尾延迟。下方草图 `refresh(true)` 为 v2 原形态记录；级联删除（deleteByDocId）维持 `refresh(true)` 不变（运维路径，删除即时可见性优先）。

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

    /** 按 chunkId 批量物理删除（v2.25 簇⑥ C1，蓝绿 diff 清理专用，bulk + refresh(true)；
     *  not_found 视为幂等成功——目标本就不在 ES） */
    public void deleteByChunkIds(List<String> chunkIds) { /* bulk delete ops */ }
}
```

> **v2.25 修正（2026-08-13，簇⑥ C1）**：`markDeleted` 软删写侧接线（此前零调用方）——
> 读侧管道早已就位（ES 检索 term filter `is_deleted=false` + 向量路 RetrievalContext
> FilterExpression 双路过滤），C1 经 `ChunkCleanupService.softDelete` 补齐写侧
> （PG is_deleted=true + ES markDeleted + 向量库物理删——向量库无软删形态，
> 恢复需重嵌入，REST 门面归 Phase 4.4）。`deleteByChunkIds` 为蓝绿 diff 清理新增。

**一致性模型**：ES 是向量库的**从属副本**——PG `kb_chunk` 为唯一事实源，ES 与向量库均可从 PG 全量重建（第十四章索引重建 API）。双写失败不阻断 ETL，由重建任务兜底。

---

## 9.5 Contextual Retrieval 增强（v2 新增，v2.23 起默认开启）

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

> **v2.21 落地（2026-08-12，簇④ A4，任务 2.4 复活）**：`ContextualEnrichmentTransformer` 按本节设计落地（`kb.etl.contextual.enabled` 默认关），实现要点：
> 1. **管道位置**：切分 → **入库消毒之后**、落库之前——LLM 只见脱敏态文本，原文 PII 不出库（与簇② B1 纵深一致）；
> 2. **装配形态**：kb-etl 不依赖 kb-ai-core（避免拖入对话链路 Advisor 栈），引 `spring-ai-openai` 实现模块（非 starter，免自动装配面——坑位⑲教训），经济模型 deepseek-v4-flash 手工装配 OpenAI 兼容形态（消费 `spring.ai.deepseek.*` @Value，temperature 0 / maxTokens 300 封顶成本），同 SmartRoutingConfig 形态；
> 3. **文档概要流转**：ETL 侧取首段非空解析文本前 N 字符（`kb.etl.contextual.excerpt-chars` 默认 2000，含文档标题行）写入 chunk 元数据 `doc_excerpt`，同一文档全部 chunk 共享（Prompt Caching 摊薄的形态基础），增强完成后移除该键不落任何存储面；原文经 `original_text` 元数据键流转落 `original_content`；
> 4. **跳过与容错**：IMAGE chunk（正文为 img 标签无语义）与 <20 字符短 chunk 跳过；单 chunk 生成失败 WARN 原样放行（质量项不阻断入库）；
> 5. **A/B 决策未定**：启用与否须经 kb-eval 双探针快照对比（全量重入库窗口：off 基线 vs on 对比，靶点 dm-13 纯表格 chunk），数据说话后定默认值并回写本节与 10 章检索形态。

> **v2.22 补充（2026-08-12，簇④ A4 并发优化）**：语境增强由**串行改有界并发**——
> 每 chunk 一次 LLM 调用，串行形态下大文档 ETL 时长 = chunk 数 × 单调用时长
> （实测数十 chunk 即分钟级阻塞上传响应）。实现：虚拟线程执行器（单例 Bean 持有，
> 非每请求 new——簇③ D2 执行器纪律同构）+ `Semaphore` 闸门（`kb.etl.contextual.concurrency`
> 默认 8，防供应商 429），槽位按输入下标写入保序返回，单 chunk 失败隔离语义不变。
> 并发实证/上限纪律/混合批次保序共 3 例单测钉死（ContextualEnrichmentTransformerTest）。

> **v2.23 A/B 定案：默认开启（2026-08-12，簇④ A4 收官）**：全量重入库 ×2 双臂
> 对比（新 Golden 102 条 chain 探针，确定性 ID 跨臂逐位复现，语料 6 文档 168 chunk
> 两臂完全同构——CSV 全量比对核验）：
>
> | 指标 | heading-only（off） | contextual-on | Δ |
> |---|---|---|---|
> | Recall@5 | 0.902 | **0.931** | +2.9pp |
> | MRR | 0.888 | **0.933** | +4.5pp |
> | Context Precision | 0.851 | **0.886** | +3.5pp |
> | Doc Recall / Doc MRR | 0.944 / 0.983 | 0.962 / **1.000** | 同向 |
> | Faithfulness | 4.813 | 4.725 | −0.088（Judge 噪声带内） |
>
> 靶点验证：dm-13（跨 3 chunk 拆分表）0.000→**0.667**——语境前缀正是表格 HTML
> 稀薄语义的唯一主题信号，与设计预期吻合；dm-02 跨块枚举 0.50→1.00；cross 多文档
> 9 例中 7 例改善（cross-05 0.50→0.75 / cross-06 0.33→0.67 / cross-07 0→0.33 等）。
> 残留：cross-08 两臂均 0（抽象聚合查询「持续时长」的语义鸿沟，属查询侧难点非
> 增强失效；标注补漏 61c58f6c 后归入下轮基线复测）；cross-09 R 0.40→0.20 为
> 5 锚点/Top-5 结构性薄边界的单 chunk 抖动（sec-04 仅排名微降）。TABLE 分类
> F 4.267→3.800 列观察项（整体均值与分类地板门禁均通过，下轮全量评估复核）。
> **结论**：检索三指标全维度改善、生成侧中性，`kb.etl.contextual.enabled` 默认
> 转 `true`（回退：`KB_ETL_CONTEXTUAL_ENABLED=false`）。入库侧代价：每 chunk 一次
> 经济模型调用（并发化后墙钟 ≈ 原 1/8）+ 增强前缀略增 embedding token。

---

## 9.6 异步与进度推送

`@Async("etlExecutor")` + `EtlProgress` 回调（Phase 1 已实现，当前仅日志输出）。Phase 2.13 扩展：

- 进度写入 Redis（`etl:progress:{docId}` Hash，TTL 24h，键规划见第七章 7.5）；
- WebSocket 端点 `/ws/etl/progress` 向前端推送 `EtlProgress`（stage/chunkCount/processedChunks/percentage）；
- 前端文档上传组件订阅进度条，完成后自动刷新文档列表状态。
