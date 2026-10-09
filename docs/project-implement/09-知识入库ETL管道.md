# 第九章：知识入库 ETL 管道

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第五卷「核心模块技术实现」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-10-04（v2.35 修复批7：AST 视图预处理——结构标签配平 + 行内 code 屏蔽；详 9.2 v2.30）
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

> **v2.21 修正（2026-08-12，冲刺簇④ A4 heading 路径元数据）**：切分时维护六级标题栈——Markdown `#{1,6} ` 行与 HTML `<h1>..<h6>` 双形态识别，每个 chunk 注入 `heading_path` 元数据（「L1 > L2 > …」）。三条实现纪律：
> 1. **标题变更即冲刷缓冲**：chunk 边界与章节边界对齐（topic-aligned），标题文字保留在新 chunk 正文首部（BM25/向量化可检索）；
> 2. **三路分发**：无保护标签且无标题 → 原快速路径零变化；仅标题无保护标签 → 纯行扫描（**不经 JSoup**——代码片段尖括号 `List<String>` 会被 JSoup 解析为未知标签丢文本）；有保护标签 → JSoup AST 路径（尖括号风险为 v2 既有边界，不扩大）；
> 3. **三存储面落地**：`kb_chunk.metadata` JSONB 的 heading_path 键 + 向量库元数据（缺省不写键，元数据禁 null）+ ES `heading_path` 字段（新建索引走 mapping ik 分词，存量索引 dynamic mapping 自动映射，完全对齐随 Phase 4.6 索引重建窗口）。载体经 `KbChunk.headingPath` @Transient 字段流转（免 ECS ALTER）。展示与检索两用；BM25 查询侧消费（multi_match 纳入 heading_path）待 contextual A/B 决策后评估，避免双重变量污染基线。

> **v2.22 修正（2026-10-04，HtmlProtectingSplitter 修复批1：结构判据精确化 + 代码围栏屏蔽）**：源码复核 + 实测（jsoup 1.23.1 / spring-ai 2.0.1）发现并修复四类缺陷；**真实语料（`docs/corpus` 6 篇）修复前后逐 chunk 字节一致——确定性 chunk ID 零漂移，Golden 锚点无需重锚**。
> 1. **统一入口判据**：结构标签判据由草图与初版的 `contains("<table")` / `contains("<img")` 改为严格边界正则 `<(table|img|h[1-6])(?=[\s/>])`（大小写不敏感）。原判据把 `<tableau>` / `<image>` 误判入 AST 路径（标签被剥、行结构被合并）；**且实测可致数据丢失**——`<imgs 伪标签`（未闭合）被 JSoup 吞掉其后全文，整篇产出 **0 chunk 静默丢库**。含 HTML 标题标签的文档一并纳入 AST 路径：原实现下「有 `<h1>`~`<h6>`、无 table/img」的文档走快速路径，heading_path 全丢且 `<h1>`/`<p>` 标签原样进 chunk 正文（参与 embedding 与提示词）。
> 2. **代码围栏屏蔽**：新增一次行扫描得出围栏区间（``` / ~~~ / 4+ 反引号 / 未闭合延伸至文末 / ≤3 空格缩进），围栏内既不参与结构判据、也不参与 Markdown 标题判定；AST 路径把围栏区间改写为 `<pre>HTML 转义原文</pre>`（**转义而非哨兵占位**——控制字符会被 HTML 解析器丢弃，哨兵方案实测污染真实语料正文），由 `pre` 字面分支原样回到 chunk 正文。原实现下围栏内 `# 注释` 被当真标题（多切一个 chunk + 污染 heading_path、影响 BM25 与向量语义）、围栏内 `<h1>` 污染标题栈、围栏内 `<table>` 代码样例被提升为真 TABLE chunk。
> 3. **判据细节**：ATX 标题按 CommonMark 收紧为 `^[ \t]{0,3}(#{1,6})[ \t]+(.+?)[ \t]*$`——支持 ≤3 空格缩进（原 `^#{1,6}` 漏识合法标题）、`[ \t]` 替换 `\s`（原整篇 `find()` 预检可跨行误匹配 `#\n正文`）、去 MULTILINE 改逐行 `matches()`；结构判据剔除 HTML 注释（`<!-- <table> -->` 不再触发 AST 路径）。**行为变化**：此类文档改走行扫描后注释文本保留在正文（原实现被 JSoup 丢弃）。
> 4. **空标题守卫**：`<h2></h2>` / `<h2>&nbsp;</h2>` / `<h2> </h2>` 不再触发冲刷——原实现先冲刷后判空，章节被劈成两个 chunk；NBSP 因 `String.isBlank()` 不覆盖，还可能作为标题进 heading_path。标题文字统一经 NBSP 归一后判空。
>
> **本批边界（已由批 2 承接关闭，见下条 v2.23）**：AST 路径在批 1 仍只遍历 body 直接子节点——嵌套（`<div>`/`<section>`）内的标题与 table/img 不识别（`<p><img/></p>`、深层 `div>section>figure>img` 的图片**零 chunk 丢失**）、块级边界（`<br>` / `<li>` / `<p>`）合并为空格、`<style>/<script>` 与 `<pre>`/`<code>` 字面语义仅因「整段 `el.text()`」而恰好不出错。快速路径边界不变：仅含 `<p>`/`<div>` 等非结构标签的文档仍原文直通（标签留在正文）。

> **v2.23 修正（2026-10-04，HtmlProtectingSplitter 修复批2：递归 DOM 遍历 + 块级边界）**：AST 路径由「body 直接子节点」改为**递归遍历**，一并落地三处递归下必须显式化的纪律（否则递归会引入新缺陷）；**真实语料 6 篇逐 chunk 字节一致**（含 TABLE chunk 与 heading_path），确定性 chunk ID 零漂移。
> 1. **递归遍历**：`walk(node)` 对任意嵌套层级分派——`h1`~`h6` 空标题守卫后冲刷入栈、`table`/`img` 保护块独立成 chunk、`<br>` 补换行、其余元素在块级前后补行边界后递归子节点。修复前实测：`<div><h1>合同条款</h1><p>…</p><table>…</table></div>` 只产出 1 个纯文本 chunk（标题与表格双双退化、保护失效）；`<p><img/></p>` 与 `div>section>figure>img` 的图片**一个 chunk 都没有**（连 `original_html` 都没落 = 图片静默不入库）。递归后二者分别恢复为「标题路径 + TABLE chunk」与 IMAGE chunk，且保护块内容不重复并入文本流。
> 2. **块级边界**：块级元素（`p`/`div`/`li`/`ul`/`section`…）前后补行边界——修复前 `el.text()` 把 `<br>` 变空格（`第一行 第二行`）、嵌套 `<ul><li>` 三项粘成一行、`<div>` 内多段落合并；这些合并直接损害 BM25 词项边界与向量语义（列表项/段落级语义被抹平）。
> 3. **字面块与跳过标签（递归下的新增纪律）**：`pre`/`code` 原文入缓冲且**不做标题扫描**（修复前靠「不递归 + 整段 `el.text()`」恰好不出错，递归后会立刻把代码里的 `# 注释` 判成假标题）；`script`/`style`/`noscript`/`title` 等不入正文（同理，修复前靠不递归才无噪声）。另：AST 文本节点换行语义改为「行间补换行、行尾不补」——修复前逐行补尾换行会让行内元素（`<b>`/`<code>`/`<span>`）处凭空断行（实测 `参考 <code># 注释</code> 的写法。` 断成两行），块级边界改由 `lineBreak` 统一负责；Markdown 行扫描路径保持「逐行补换行」不变（与该路径初版逐字一致）。
>
> **v2.24 修正（2026-10-04，HtmlProtectingSplitter 修复批3：评审热修复）**：对修复批1/批2 的独立评审提出六项，逐条实证核实（四项复现为真、两项判为非必须/吹毛求疵）后落地前四项；**真实语料 6 篇仍逐 chunk 字节一致**（语料既无 HTML 注释亦无小表格 → 本批对既有语料零影响）。
> 1. **注释判据同口径**（评审问题 1，中等）：原实现只对「结构标签」判据剥注释，Markdown 标题判据与路径 1 行扫描未排除注释 → 多行注释内的 `# 行` 被当真标题冲刷并进 heading_path（与「判据只看围栏与注释之外」的声明矛盾，实测 `<!--
# 这不是标题
-->` 产出 `heading_path=这不是标题`）。治法 = 行扫描产出**注释屏蔽标记**（`Scan.inComment`）与围栏区间并列，判据与路径 1 共用。**规则收紧为「行首处于注释内」**而非「行内出现注释」——ATX 标记必为行首非空白字符，故两者对标题等价，但前者不会误伤 `# 标题 <!-- 注 -->`（行尾行内注释的标题语义保留，反向守卫单测钉死）；未闭合注释延伸至文末；围栏行不参与注释状态机（否则围栏内的 `<!--` 会把其后的真实标题一并屏蔽）。**连带修正**：标题文字归一新增行内注释剔除（原 `# 标题 <!-- 注 -->` 的 heading_path 会带上注释标记；剔除后与 AST 路径 `el.text()` 口径一致），仍只作用于标题文字，正文中的注释文本原样保留。
> 2. **小表格退化补行边界**（评审问题 2）：`lineBreak(buffer)` 前置——行内上下文（`<span>a</span><table>短</table>`）原产出「a短」粘连，现与块级上下文同形。
> 3. **行内上下文不判 Markdown 标题**（评审问题 3）：`walk` 传播块级上下文标志，行内元素（`<b>`/`<span>` 等）内多行文本的行首 `# ` 不再产生标题语义（原 `<b>前缀
# 行
后缀</b>` 会冲刷章节并进 heading_path）；**块级元素与顶层文本节点行为不变**（DocMind 正文即顶层 Markdown 文本；`<p>` 等块级上下文仍识别块内 Markdown 标题，单测双向钉死）。同步去掉调用侧重复的行首表达式——行首判据统一由 `appendLine` 内部执行（评审问题 6）。
> 4. **微整理**（评审问题 5）：`flushBuffer` 单次 `toString()`（原判空分支与正文构造各复制一次缓冲副本）；`Scan` 记录注释为「新增结构判据时的区间标记扩展点」（评审建议 3 的区间查询 API 属推测性抽象，本轮不引入）。
> 5. **围栏规则声明（有意简化，不改行为）**（评审问题 4）：围栏按「≤3 空格缩进 + 3 个以上同字符」识别，**不校验 CommonMark 的 info string 约束**（反引号围栏的 info string 不得含反引号）——该差异仅影响病态输入（``` a`b 一行），实证「修正」反而会抑制其后的真实标题（闭合标记被当作新的开启标记），故明确声明而不引入半个 CommonMark 解析器。
> 6. **回归资产沉淀**（评审建议 2）：语料漂移守卫单测 `HtmlProtectingSplitterCorpusDriftTest`——逐篇校验 ①chunk 总数与 TABLE chunk 数基线 ②每个 chunk 的 heading_path 逐段必须能在文档真实 Markdown 标题行中找到（围栏/注释内伪标题泄漏会立刻暴露）；语料目录不在相对位置时整类跳过。切分器单测 31 → 36 例（新增多行注释/行尾注释反向守卫/未闭合注释/行内元素多行文本与块级对照/行内小表格边界）。

> **v2.25 修正（2026-10-04，HtmlProtectingSplitter 修复批4：短文本静默丢弃根治）**：用户侧对语料复入库后的 chunk 导出复核发现「章节标题在正文中缺失」，逐条实证定位到 **Spring AI `TokenTextSplitter` 的静默丢弃路径**——`doSplit` 的入库判据是 **严格大于** `minChunkLengthToEmbed`（{@code chunkTextToAppend.length() > this.minChunkLengthToEmbed}，本切分器取默认 10），≤10 字符的 flush 文本直接丢弃，而 {@code flushBuffer} 原样忽略返回值 → **文本静默消失**。
> 1. **触发条件**：容器标题（标题行后紧跟更深标题、该章节本身无正文）或表格/图片前的短标题，且标题 ≤10 字符——此时该标题单独成一次 flush，整段被丢。实测：DDD 文档「一、DDD 概述」(8)、「八、系统演进模式」(8)；企业信息安全文档「一、总则」(4)、「二、数据分级分类」(8)、「6.1 事件分级」(8，其后直接是表格）。同文档 >10 字符的容器标题（「六、CQRS（命令查询职责分离）」16、「二、通用语言（Ubiquitous Language）」27）均正常成 chunk——阈值行为完全吻合。危害：标题文字**在任何 chunk 正文中都不存在**（仅存于后续 chunk 的 heading_path），BM25 与向量化都检索不到该章节名，且违反 v2.21 纪律 1「标题文字保留在新 chunk 正文首部」。
> 2. **治法（方案 B：残余随其后继内容落位）**：{@code flushBuffer} 返回未成 chunk 的残余文本；后继为标题（容器标题）→ 回填缓冲，父标题文字进入子章节 chunk 正文（heading_path 本就是其前缀链，语义自洽）；后继为保护块 → 前置到该保护块 chunk 正文（`6.1 事件分级
<table>…`），**{@code original_html} 恒为纯 HTML**——结构保真与前端回显不受影响；文末无后继 → 兜底自成 chunk（不再有丢内容的出口）。另：该丢弃路径对「文末极短残余」同样生效，同一治法一并覆盖。
> 3. **实测读数**：6 篇语料 chunk 数**全部不变**（无索引位移）；仅 2 篇受影响文档的 5 个 chunk 正文变化——DDD{1,55}、企业信息安全{1,4,18}；另 4 篇（K8s/发票/产品/阿里云 MD）**逐 chunk 字节一致**（无需重入库）。Golden 锚点影响：{@code security-qa} 2 个 ID（sec-01/09/12 的「2.1 密级定义」+ sec-13 的 TABLE chunk）需重锚；{@code multihop-qa} 30 个 DDD 锚点与 cross-02/sec-06 不受影响。
> 4. **回归资产**：语料漂移守卫新增第三条不变量——**每个 Markdown 标题文字必须至少出现在一个 chunk 正文中**（本缺陷的直接指纹，修复前必挂）；切分器单测 36 → 40 例（容器标题归位 / 表格前短标题前置且 original_html 纯净 / 文末兜底 / 短标题后有正文时行为不变）。
> 5. **定位方法留档**：确定性 chunk ID 可在本地逐条复现（`UUID.nameUUIDFromBytes(name#index#text)`，Tika 文本 → 切分器 → 同一算式），本次以用户导出 CSV **71/71 逐条对齐**后据此测算影响面——后续评审/热修可复用该手法先算影响再改码。

> **v2.26 修正（2026-10-04，HtmlProtectingSplitter 修复批5：容器标题归位的布局保真）**：批4 归位容器标题时对残余文本做了 {@code strip()}，把源文档「容器标题 / 空行 / 子标题（或表格）」三段布局里的**空行吃掉了**——chunk 正文与原文不一致（用户侧复入库观测发现）。治法 = **残余文本原样衔接**：{@code carryInto} 不再 strip（仅补行边界），{@code protectedChunk} 的前缀只去前导空白、保留其后空行。实测：受影响 chunk 正文由「{@code 一、DDD 概述\n1.1 起源与背景}」变为「{@code 一、DDD 概述\n\n1.1 起源与背景}」（表格场景同理：{@code 6.1 事件分级\n\n<table>}），**chunk 数全部不变**（6 篇语料仍 8/28/7/8/19/71），仅 2 篇受影响文档的 5 个 chunk 正文与 ID 变化——需再重入库一次。
>
> **口径**：chunk 正文的换行结构以源文档为准（标题与其后内容的空行属原文布局，不再由切分器改写）；chunk 边界的 trim 仍由 {@code TokenTextSplitter} 负责（首尾空白不入 chunk）。

> **v2.27 修正（2026-10-04，HtmlProtectingSplitter 修复批6：保护块原文对齐）**：批4/批5 让无正文短标题以前缀形式进入保护块 chunk 正文（BM25/向量化可检索），但 `original_html` 仍只记纯 HTML → 落库后 **`content` 有标题而 `original_content` 没有**（用户侧复入库观测发现：企业文档「6.1 事件分级」行）。治法 = `original_html` 同记**「前缀 + 保护块 HTML」原文** → 全类型 chunk 统一语义：`original_content` = 语境增强前原文（保护块即为含前置标题的原文），`content` = 增强文本（未增强时二者**同值**）；保护块 HTML 原样保留在其内，结构保真不受影响。**无 ID 影响**（确定性 ID 的 `baseText` 取原文，取值不变），存量行随重入库窗口自然对齐。**观测口径**：`content` 去掉「【上下文】…\n\n」前缀即应等于 `original_content`（增强 chunk），未增强 chunk 两列同值——可作导出复核的通用不变量。

> **v2.30 AST 视图预处理（2026-10-04，HtmlProtectingSplitter 修复批7）**：用户侧提问「`docs/corpus/企业信息安全与数据保护管理办法.md` 这类含 `<table>` 的 Markdown 走的是 JSoup 路径吗？JSoup 不转换能处理 Markdown 吗」复核时发现——**是走 AST 路径**（`hasProtected=true`，六篇语料中唯此一篇），且 JSoup 无需转换即可处理 Markdown 的原因要讲清楚：Markdown 标记（`#`/`|`/`**`）对 HTML 解析器只是惰性文本，**Markdown 标题语义由本切分器在 TextNode 之上逐行判 ATX 得出**（两条路径共用 `appendLine`），围栏内容早已被转义为 `<pre>` 不进解析器视野。但由此暴露一条此前未覆盖的规律：**凡交给 JSoup 的文本都受 HTML 词法约束**——两类缺陷实测复现并修复（三项变换统一收在 `astView`，只作用于围栏与注释之外的文本）：
> 1. **未闭合结构标签吞并后文**（HTML 解析器把未闭合元素延伸到父级结束）：`<table>` 缺 `</table>` ⇒ 其后全文落入表格元素，产出**一个 1871 字符**的 TABLE chunk（实测企业文档 28 → 19 chunk，6.2/6.3/七/八/九 五个章节一并被吞、尾部 Markdown 标题失去语义、`###` 标记漏进正文）；`<h2>` 缺闭合 ⇒ 其后正文成为**标题文字**，heading_path 污染成含正文的 65 字长串。治法 = `balanceStructure` 解析前补足缺口：标题标签按整篇 LIFO 配对，**落单的 `<hN>` 在其所在行行尾补闭合**（HTML 标题文字天然同行；跨行书写的合法标题配对完整、不受影响）；表格结构标签按「整篇计数缺口 + 最后一个结构闭合行（`</td>`/`</th>`/`</tr>`/`</tbody>`）」按内→外补足，无结构闭合行可定位时退回该表起始行行尾。**两条不猜原则**：最后一个 `<table>` 出现在边界行之后、或无缺口——原样返回；
> 2. **字面尖括号被当作未知标签吞掉**：行内 code 里的 `<svc>.<ns>.svc.cluster.local`、`Optional<List<String>>`、`<v1.2>` 在 AST 路径下标签名连同紧随字符消失（实测 `签名 Optional<List<String>> query` → `签名 Optional> query`）。治法 = `shieldInlineCode` 把成对反引号区间内的 HTML 元字符转义为实体（与围栏屏蔽同一机制，解析时还原为原文）；反引号**按长度两两配对**（`` ``a`b`` `` 也能正确闭合），**同长度出现落单反引号时整行不屏蔽**——否则落单标记会把同一行的真标签（如行内 `<table>`）一并转义，保护反而失效。
>
> **实测读数**：三项变换均无命中时输出与原实现**逐字节一致**——六篇语料 chunk 计数（8/28/7/8/19/71）与 TABLE 计数（0/1/0/0/0/0）零漂移、ID 零漂移（无需重入库）；缺陷场景修复后：缺 `</table>` 的企业文档恢复 28 chunk、TABLE 块 1871 → 575 字符（≈ 完好值）、尾部章节各自成块；缺 `</h2>` 从 2 chunk 恢复 4 chunk 且 heading_path 与闭合完好时逐段一致；四类字面尖括号 4/4 保真（`astView` 只改解析视图、不改正文语义：文本型 chunk 呈现解码后的字面量，保护块 HTML 内呈现 HTML 实体形态——即该字面量在 HTML 中的唯一合法写法）。
>
> **关联解读（解答提问）**：AST 路径的输入是「解析器产出的文本」，其形态由上传文件类型决定——`.md` 上传经 Tika 原样透传（HTML 孤岛保留，Markdown 标题走行扫描）、DocMind（DEEP）路由的正文为 `markdownContent`、表格 HTML 为 **LLM 产出的 `llmResult`**（9.1 v2.2 实证注记）——后者正是「未闭合标签」的现实来源，故本批按健壮性缺陷而非理论边界处理。

> **两条路径的边界（修复后最终口径）**：快速路径 = 无结构标签且无标题的纯文本（原文直通，行为与 Phase 1 一致）；行扫描路径 = 仅 Markdown 标题（不经 JSoup，代码尖括号安全）；AST 路径 = 含 `table`/`img`/`h1`~`h6`（围栏外）的文档，正文去标签、保护块成 chunk、块级边界保留，且解析前经 `astView` 三项预处理（围栏屏蔽 / 行内 code 屏蔽 / 结构标签配平）。`<p>`/`<div>` 等非结构标签单独出现时仍走快速路径（标签留在正文）——这是「不引入 JSoup 全量解析」纪律的代价，属既定边界。

> **配平规则边界（有意简化，v2.30）**：表格结构标签按「整篇计数缺口 + 最后一个结构闭合行」定位补足点，不重建完整标签栈——覆盖「单表缺一个闭合标签」（LLM 产出 HTML 与手写 Markdown 嵌 HTML 的常见形态）；**多表且非末表未闭合、表格 HTML 被截断在行中、结构标签之外的未闭合元素（`<div>`/`<p>`，递归遍历照常取文本、仅有行边界差异）** 均不处理或无需处理。行内 code 屏蔽只对「该行反引号按长度严格成对」的行生效（保守：宁可不屏蔽，不可误转义真标签）。

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

> **v2.22 修正（2026-08-12，冲刺簇④ A4 检索锚点修复）——chunk ID 确定性化**：
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

> **v2.25 修正（2026-08-13，优化冲刺簇⑥ C1 增量重入库）——蓝绿管线与增量 API**：
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

> **v2.26 修正（2026-08-13，优化冲刺簇⑥ C1 E2E 缺陷修复）——占用态回写与 created_at 覆写**：
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

> **v2.27 修正（2026-08-13，优化冲刺簇⑥ C1 收尾）——删除处理期守卫**：
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

> **v2.19 修正（2026-08-11，冲刺簇③ D2）**：批量写入刷新策略 `refresh(true)` → `Refresh.WaitFor`——语义仍为「返回即可检索」（请求挂起至下一次刷新周期完成），但避免大文档 ETL 尾部每批强制全索引刷新的长尾延迟。下方草图 `refresh(true)` 为 v2 原形态记录；级联删除（deleteByDocId）维持 `refresh(true)` 不变（运维路径，删除即时可见性优先）。

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

    /** 按 chunkId 批量物理删除（v2.25 优化冲刺簇⑥ C1，蓝绿 diff 清理专用，bulk + refresh(true)；
     *  not_found 视为幂等成功——目标本就不在 ES） */
    public void deleteByChunkIds(List<String> chunkIds) { /* bulk delete ops */ }
}
```

> **v2.25 修正（2026-08-13，优化冲刺簇⑥ C1）**：`markDeleted` 软删写侧接线（此前零调用方）——
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
 * - 使用经济模型（deepseek-flash）生成上下文。</p>
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

> **v2.21 落地（2026-08-12，冲刺簇④ A4，任务 2.4 复活）**：`ContextualEnrichmentTransformer` 按本节设计落地（`kb.etl.contextual.enabled` 默认关），实现要点：
> 1. **管道位置**：切分 → **入库消毒之后**、落库之前——LLM 只见脱敏态文本，原文 PII 不出库（与冲刺簇② B1 纵深一致）；
> 2. **装配形态**：kb-etl 不依赖 kb-ai-core（避免拖入对话链路 Advisor 栈），引 `spring-ai-openai` 实现模块（非 starter，免自动装配面——坑位⑲教训），经济模型 deepseek-flash 手工装配 OpenAI 兼容形态（消费 `spring.ai.deepseek.*` @Value，temperature 0 / maxTokens 300 封顶成本），同 SmartRoutingConfig 形态；
> 3. **文档概要流转**：ETL 侧取首段非空解析文本前 N 字符（`kb.etl.contextual.excerpt-chars` 默认 2000，含文档标题行）写入 chunk 元数据 `doc_excerpt`，同一文档全部 chunk 共享（Prompt Caching 摊薄的形态基础），增强完成后移除该键不落任何存储面；原文经 `original_text` 元数据键流转落 `original_content`；
> 4. **跳过与容错**：IMAGE chunk（正文为 img 标签无语义）与 <20 字符短 chunk 跳过；单 chunk 生成失败 WARN 原样放行（质量项不阻断入库）；
> 5. **A/B 决策未定**：启用与否须经 kb-eval 双探针快照对比（全量重入库窗口：off 基线 vs on 对比，靶点 dm-13 纯表格 chunk），数据说话后定默认值并回写本节与 10 章检索形态。

> **v2.22 补充（2026-08-12，冲刺簇④ A4 并发优化）**：语境增强由**串行改有界并发**——
> 每 chunk 一次 LLM 调用，串行形态下大文档 ETL 时长 = chunk 数 × 单调用时长
> （实测数十 chunk 即分钟级阻塞上传响应）。实现：虚拟线程执行器（单例 Bean 持有，
> 非每请求 new——冲刺簇③ D2 执行器纪律同构）+ `Semaphore` 闸门（`kb.etl.contextual.concurrency`
> 默认 8，防供应商 429），槽位按输入下标写入保序返回，单 chunk 失败隔离语义不变。
> 并发实证/上限纪律/混合批次保序共 3 例单测钉死（ContextualEnrichmentTransformerTest）。

> **v2.23 A/B 定案：默认开启（2026-08-12，冲刺簇④ A4 收官）**：全量重入库 ×2 双臂
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

> **v2.28 语境增强挂辅助族（2026-09-01，批B 追随修复）**：主模型双形态批B 退役
> `spring.ai.deepseek.*` 配置族后，v2.21 装配的 `spring.ai.deepseek.*` @Value 取值
> 落空 → 空密钥快失败（启动报 `DEEPSEEK_API_KEY 未配置`）。定案迁辅助族：模型
> **deepseek-flash → qwen3.8-flash**（与备用/路由改写/图抽取同族，DashScope
> compatible-mode），配置改自持三键 `kb.etl.contextual.{api-key,base-url,model}`
> （key 缺省回落 `DASHSCOPE_API_KEY`——项目必有 key，不再依赖可选 DEEPSEEK key），
> 并按坑位⑮显式 `enable_thinking=false`（摘要类轻任务防每 chunk 思维链税）。
> temperature 0 / maxTokens 300 / 并发闸门不变。**注**：增强模型变更即
> chunk `content` 增强前缀形态变更——存量语料经重入库窗口复现后 chain 探针基线
> 需复测对档（与 v2.23 A/B 同法）。

> **v2.29 未增强 chunk 原文对齐（2026-10-04，用户侧复入库观测微调）**：此前
> **未增强路径**（IMAGE / 超短 < `MIN_ENRICH_CHARS`(20) / 空语境 / 异常原样放行）不打
> `original_text` 标记 → 落库 `original_content` 为 **NULL**，而 `content` 有值——同一份文本
> 在两列间"半空"，运维/观测面每次都要解释「NULL = 未增强」这一隐含语义。治法 =
> `stripExcerpt` 更名 `passthrough` 并在放行时**标记原文**（{@code text} 非空白且未标记时写入
> `ORIGINAL_TEXT_KEY`）→ 未增强 chunk 的 `original_content` 与 `content` **对齐同值**；
> IMAGE/TABLE 仍以保护块 HTML 为准（ETL 侧 `originalHtml` 优先且不覆盖，语义不变）。
> **无 ID 影响**：确定性 ID 的 `baseText` 取「增强前原文」，标记与否取值相同（该键值即正文），
> 故本次改动不引起重锚；存量行随重入库窗口自然对齐（不单独回填）。

---

## 9.6 异步与进度推送

`@Async("etlExecutor")` + `EtlProgress` 回调（Phase 1 已实现，当前仅日志输出）。Phase 2.13 扩展：

- 进度写入 Redis（`etl:progress:{docId}` Hash，TTL 24h，键规划见第七章 7.5）；
- WebSocket 端点 `/ws/etl/progress` 向前端推送 `EtlProgress`（stage/chunkCount/processedChunks/percentage）；
- 前端文档上传组件订阅进度条，完成后自动刷新文档列表状态。
