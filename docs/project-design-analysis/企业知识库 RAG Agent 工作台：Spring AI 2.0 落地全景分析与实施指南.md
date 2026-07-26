# 企业知识库 RAG Agent 工作台：Spring AI 2.0 落地全景分析与实施指南

> **项目定位**：面向企业复杂文档场景的高可用、可溯源、可运维的 RAG Agent 知识库工作台
> 
> **技术基座**：Java 21 (虚拟线程) + Spring Boot 4.1 + Spring AI 2.0.0 GA + Milvus + Elasticsearch
> 
> **报告目标**：将 Python/FastAPI/LangChain 体系下的优秀业务设计，完美映射并升华至 Java/Spring AI 2.0 企业级架构体系，提供从 0 到 1 的硬核落地指南。

---

## 一、 核心痛点与 Spring AI 2.0 破局映射

原项目精准抓住了企业 RAG 的五大痛点。在 Spring AI 2.0 体系下，我们将通过其最新的模块化架构、Advisor 机制和 ToolCallingManager 逐一破局：

| 业务痛点 | 原 Python/LangChain 方案 | **Spring AI 2.0 企业级破局方案** |
| :--- | :--- | :--- |
| **文档解析难** (表格/扫描件) | 原生解析 + OCR 动态路由 | **Spring AI DocumentReader** + 自定义 `SmartOcrRoutingReader` (结合 Tika 与 OCR API) |
| **切分碎片化** (结构丢失) | 半结构化保护式切分 | 自定义 `HtmlProtectingTextSplitter` (基于 AST/正则解析保护 `<table>`/`<img>` 节点) |
| **纯向量检索不稳** (字段名命中低) | Milvus + BM25 + RRF 融合 | **Spring AI VectorStore** (Milvus) + **Elasticsearch** (BM25) + 自定义 `RrfFusionReranker` |
| **回答无依据/幻觉** | Prefetch 预检索注入 | **自定义 PrefetchAdvisor** (拦截器前置检索) + Grounding Prompt 模板 |
| **链路割裂/难调试** | 日志落库 + 前端观测 | **Spring AI Observability** (Micrometer/OTel) + 自定义 `AuditTraceAdvisor` 全链路埋点 |
| **Agent 扩展难** | LangGraph 构建 Agent | **ChatClient + ToolCallingManager + MCP** 标准化 Agent 编排 |

---

## 二、 系统架构设计 (基于 Spring AI 2.0 模块化理念)

抛弃单体架构，采用 Spring AI 2.0 倡导的**领域驱动分层架构**。利用 Java 21 虚拟线程（Virtual Threads）完美解决 RAG 链路中密集的 I/O 阻塞问题（如并发检索、OCR 调用、LLM 流式等待）。

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                  接入层 (Vue3 工作台 + Spring Cloud Gateway)              │
│   [文档管理]   [Chunk 观测]   [检索调试台]   [Agent 对话(SSE)]   [运维]      │
└──────────────────────────────┬──────────────────────────────────────────┘
                               │ (REST / SSE / WebSocket)
┌──────────────────────────────▼──────────────────────────────────────────┐
│                  AI 编排层 (Spring AI 2.0 Orchestration)                 │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      ChatClient (Fluent API)                     │   │
│  │  ┌────────────┐ ┌──────────────┐ ┌────────────┐ ┌────────────┐   │   │
│  │  │ RateLimit  │→│ Prefetch RAG │→│  Memory    │→│ AuditTrace │   │   │
│  │  │  Advisor   │ │   Advisor    │ │  Advisor   │ │  Advisor   │   │   │
│  │  └────────────┘ └──────────────┘ └────────────┘ └────────────┘   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────────┤
│                  能力层 (Spring AI 2.0 Capability)                       │
│  ┌──────────────┐  ┌──────────────────┐  ┌──────────────────────────┐   │
│  │ToolCalling   │  │ HybridRetriever  │  │ DocumentETL Pipeline     │   │
│  │Manager       │  │ (Milvus + ES +   │  │ (Tika + OCR Router +     │   │
│  │(Agent 工具)   │  │  RRF Reranker)   │  │  HTML Splitter)          │   │
│  └──────────────┘  └──────────────────┘  └──────────────────────────┘   │
├─────────────────────────────────────────────────────────────────────────┤
│                  基础设施层 (Infrastructure)                              │
│  [PostgreSQL (主数据/元数据)] [Milvus (向量)] [Elasticsearch (BM25)]       │
│  [Redis (缓存/限流/记忆)]    [MinIO (文档OSS)]    [OpenTelemetry]          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 三、 核心业务模块与 Spring AI 2.0 技术实现 (硬核实战)

### 3.1 知识入库链路：双路解析与保护式切分 (Document ETL)

**业务要求**：Document -> Section -> Chunk -> Vector 分层解耦；原生+OCR双链路；保护表格/图片结构。

**Spring AI 2.0 实现方案**：

摒弃简单的 `TikaDocumentReader`，实现自定义的 `SmartDocumentReader` 和 `HtmlProtectingSplitter`。

```java
// 1. 智能路由 Reader：根据启发式规则决定走 Tika 还是 OCR
@Component
public class SmartOcrRoutingReader implements DocumentReader {
    private final TikaDocumentReader tikaReader;
    private final OcrApiClient ocrApiClient; // 调用外部 OCR 服务 (如阿里云/百度OCR)

    @Override
    public List<Document> get() {
        Resource resource = getResource();
        // 启发式探测：如果是扫描件 PDF (文本密度极低) 或 包含大量复杂表格
        if (isScannedPdf(resource) || hasComplexTables(resource)) {
            // 走 OCR 链路，返回带有 <table> <img> 标签的 HTML 格式 Document
            return ocrApiClient.parseToHtml(resource); 
        }
        // 走原生 Tika 链路
        return tikaReader.get();
    }
}

// 2. 半结构化保护式切分器
@Component
public class HtmlProtectingSplitter implements DocumentTransformer {
    private final TokenTextSplitter defaultSplitter;

    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            // 使用 JSoup 或正则提取 <table>...</table> 和 <img...> 块
            List<String> protectedBlocks = extractProtectedBlocks(doc.getText());
            String textWithoutBlocks = removeProtectedBlocks(doc.getText());
            
            // 对纯文本部分进行常规 Token 切分
            List<Document> textChunks = defaultSplitter.apply(
                List.of(new Document(textWithoutBlocks, doc.getMetadata()))
            );
            
            // 将保护块作为独立 Chunk 或附加到相邻文本 Chunk 的元数据中
            for (String block : protectedBlocks) {
                Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                meta.put("chunk_type", block.startsWith("<table") ? "TABLE" : "IMAGE");
                meta.put("original_html", block);
                chunks.add(new Document(block, meta));
            }
            chunks.addAll(textChunks);
        }
        return chunks;
    }
}
```

**分层存储设计 (PostgreSQL + Milvus)**：
- `kb_document` 表：存文档主数据 (ID, 名称, OSS路径, 状态)。
- `kb_chunk` 表：存切分块主数据 (ID, doc_id, content, html_block, page_num, section_id)。
- **Milvus**：只存 Chunk 的 Vector 和 `chunk_id` (外键关联 PG)，实现向量与主数据解耦，支持 Chunk 在 PG 中随意编辑，只需异步更新 Milvus 向量即可。

---

### 3.2 混合检索与 RRF 融合排序 (Hybrid Retrieval)

**业务要求**：Milvus 向量 + BM25 关键词 + RRF 融合，解决专有名词/字段名命中率低的问题。

**Spring AI 2.0 实现方案**：

利用 Java 21 虚拟线程并发执行双路检索，并通过自定义 `Reranker` 实现 RRF (Reciprocal Rank Fusion) 算法。

```java
@Service
public class HybridRetrievalService {
    private final MilvusVectorStore milvusStore; // Spring AI 内置支持
    private final ElasticsearchClient esClient;  // BM25 检索
    private final RrfFusionReranker reranker;

    // 利用虚拟线程并发检索，极大降低 I/O 延迟
    public List<RetrievalResult> hybridSearch(String query, int topK) {
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            // 1. 向量检索分支
            Subtask<List<Document>> vectorTask = scope.fork(() -> 
                milvusStore.similaritySearch(SearchRequest.query(query).withTopK(topK * 2))
            );
            // 2. BM25 关键词检索分支
            Subtask<List<EsHit>> bm25Task = scope.fork(() -> 
                esClient.search(query, topK * 2)
            );
            scope.join();
            
            // 3. RRF 融合排序
            return reranker.fuse(vectorTask.get(), bm25Task.get(), topK);
        }
    }
}

// RRF 融合算法实现
@Component
public class RrfFusionReranker {
    private static final int K = 60; // RRF 常数

    public List<RetrievalResult> fuse(List<Document> vectorHits, List<EsHit> bm25Hits, int topK) {
        Map<String, RetrievalResult> scoreMap = new HashMap<>();
        
        // 计算向量 RRF 分数
        for (int i = 0; i < vectorHits.size(); i++) {
            String id = vectorHits.get(i).getId();
            double score = 1.0 / (K + i + 1);
            scoreMap.computeIfAbsent(id, RetrievalResult::new).addScore(score, "VECTOR");
        }
        // 计算 BM25 RRF 分数
        for (int i = 0; i < bm25Hits.size(); i++) {
            String id = bm25Hits.get(i).id();
            double score = 1.0 / (K + i + 1);
            scoreMap.computeIfAbsent(id, RetrievalResult::new).addScore(score, "BM25");
        }
        
        // 按融合总分降序排序
        return scoreMap.values().stream()
                .sorted(Comparator.comparingDouble(RetrievalResult::getTotalScore).reversed())
                .limit(topK)
                .toList();
    }
}
```

---

### 3.3 Agent 对话链路：Prefetch 预检索与全链路溯源

**业务要求**：Prefetch 预检索注入上下文；多轮对话；流式 SSE；返回包含各项分数和来源的溯源元数据。

**Spring AI 2.0 实现方案**：

这是 Spring AI 2.0 **Advisor 机制**的最佳秀场。我们将 Prefetch、Memory、Trace 全部抽象为 Advisor，实现业务逻辑与 LLM 调用的彻底解耦。

#### 1. 自定义 PrefetchRagAdvisor (核心)

取代原 Python 中在业务代码里手动拼装 Prompt 的做法，使用 Advisor 在请求到达 LLM 前自动拦截、检索、注入证据。

```java
@Component
public class PrefetchRagAdvisor implements CallAroundAdvisor, StreamAroundAdvisor {
    private final HybridRetrievalService retrievalService;
    private final PromptTemplate groundingTemplate;

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        // 1. 拦截用户原始 Query
        String userQuery = request.userText();
        
        // 2. 执行混合检索 (Prefetch)
        List<RetrievalResult> results = retrievalService.hybridSearch(userQuery, 5);
        
        // 3. 构建带溯源标记的上下文证据
        String evidence = buildGroundedEvidence(results);
        
        // 4. 改写 Prompt，注入证据和严格的 Grounding 指令
        String newSystemPrompt = groundingTemplate.render(Map.of(
            "evidence", evidence,
            "original_system", request.systemText()
        ));
        
        AdvisedRequest augmentedRequest = AdvisedRequest.from(request)
                .withSystemText(newSystemPrompt)
                // 将检索结果放入 AdviseContext，传递给后续 Advisor 或返回给前端
                .withAdviseContext(Map.of("rag_trace", results)) 
                .build();
                
        // 5. 继续执行链 (调用 LLM)
        return chain.nextAroundCall(augmentedRequest);
    }
    
    private String buildGroundedEvidence(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder("【参考资料】\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            // 强制模型使用 [ref-N] 格式引用
            sb.append(String.format("[ref-%d] (来源:%s, 页码:%d, 融合分:%.2f)\n%s\n", 
                i+1, r.getFileName(), r.getPageNum(), r.getTotalScore(), r.getContent()));
        }
        return sb.toString();
    }
    // ... aroundStream 同理 ...
}
```

#### 2. 组装企业级 ChatClient

```java
@Configuration
public class AgentChatClientConfig {
    @Bean
    public ChatClient knowledgeAgent(
            ChatClient.Builder builder,
            ChatMemory redisChatMemory,
            PrefetchRagAdvisor prefetchRagAdvisor,
            AuditTraceAdvisor auditTraceAdvisor) {
                
        return builder
                .defaultSystem("""
                    你是企业知识库专家。必须且只能基于【参考资料】回答。
                    若资料中无相关信息，请明确回答“知识库中未找到相关信息”。
                    每个事实性陈述必须使用 [ref-N] 格式标注来源。
                    """)
                .defaultAdvisors(
                    new MessageChatMemoryAdvisor(redisChatMemory), // 多轮记忆
                    prefetchRagAdvisor,                            // 核心 RAG 拦截
                    auditTraceAdvisor                              // 审计与日志落库
                )
                .build();
    }
}
```

#### 3. SSE 流式输出与溯源事件推送

前端工作台需要实时看到“正在检索”、“正在生成”以及最终的“溯源卡片”。Spring AI 2.0 的 `Flux` 流式 API 完美契合。

```java
@RestController
@RequestMapping("/api/agent")
public class AgentStreamController {
    private final ChatClient knowledgeAgent;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamChat(
            @RequestParam String conversationId, 
            @RequestParam String query) {
            
        return knowledgeAgent.prompt()
                .user(query)
                .advisors(spec -> spec.param("chat_memory_conversation_id", conversationId))
                .stream()
                .chatResponse()
                .map(response -> {
                    // 解析 Spring AI 的 ChatResponse，提取 Token 或结束标志
                    if (response.getResult() != null && response.getResult().getOutput().getText() != null) {
                        return ServerSentEvent.<AgentStreamEvent>builder()
                                .event("TOKEN")
                                .data(new TokenEvent(response.getResult().getOutput().getText()))
                                .build();
                    }
                    // 流结束时，从 Metadata 中提取 RAG 溯源数据
                    if (response.getMetadata() != null) {
                         List<RetrievalResult> trace = (List<RetrievalResult>) response.getMetadata().get("rag_trace");
                         return ServerSentEvent.<AgentStreamEvent>builder()
                                .event("TRACE")
                                .data(new TraceEvent(trace)) // 推送包含分数、页码的溯源卡片数据
                                .build();
                    }
                    return ServerSentEvent.<AgentStreamEvent>builder().event("HEARTBEAT").build();
                });
    }
}
```

---

### 3.4 知识库运维与全链路观测 (Ops & Observability)

**业务要求**：Chunk 编辑/删除、索引重建；完整的调试与观测能力。

**Spring AI 2.0 实现方案**：
1. **Chunk 运维**：由于采用了 PG(主数据) + Milvus(向量) 解耦设计。用户在 Vue3 工作台编辑 PG 中的 Chunk 文本后，通过 Spring 的 `@Async` (或虚拟线程) 异步调用 `EmbeddingModel` 重新向量化，并调用 `MilvusVectorStore.update()` 更新向量副本。
2. **全链路观测**：Spring AI 2.0 原生集成 Micrometer。只需在 `application.yml` 开启观测，即可在 Grafana/Prometheus 中看到：
    - `spring.ai.vector.store.search.duration` (向量检索耗时)
    - `spring.ai.chat.model.token.usage` (Token 消耗)
    - 结合自定义的 `AuditTraceAdvisor`，将每次问答的 Query、LLM 原始输出、命中的 Chunk ID、各项检索分数持久化到 PostgreSQL 的 `kb_audit_log` 表，供前端“检索调试台”进行 Bad Case 分析。

---

## 四、 业务功能扩展与补充 (基于 2026 年市场需求)

除了原文档中的基础功能，结合当前企业级 RAG 市场的最新需求，建议补充以下高级业务功能：

1. **多租户与数据隔离 (Multi-Tenancy)**
  - **实现**：在 `Document` 和 `Chunk` 的 Metadata 中注入 `tenant_id`。在 Milvus 和 Elasticsearch 检索时，通过 Spring AI 的 `FilterExpression` 强制追加 `tenant_id == 'xxx'` 的过滤条件，实现 SaaS 化数据隔离。
2. **知识库权限管控 (RBAC for Knowledge)**
  - **实现**：文档和 Chunk 绑定可见部门/角色。在 `PrefetchRagAdvisor` 中，根据当前登录用户的权限，动态生成检索过滤表达式，确保“什么级别的人只能搜到什么级别的知识”。
3. **对话反馈与自动微调闭环 (RLHF/Feedback Loop)**
  - **实现**：前端增加 👍/👎 按钮。用户点踩时，弹窗收集“期望的正确回答”。数据落入 `kb_feedback` 表，定期导出为 JSONL 格式，用于企业私有模型的 SFT（监督微调）。
4. **Agent 工具调用扩展 (Tool Calling)**
  - **实现**：除了 RAG 检索，Agent 还需要查询实时数据（如“帮我查一下公司今天的股价并对比知识库里的财报”）。通过 Spring AI 2.0 的 `@Tool` 注解，将内部 ERP、OA 系统的 API 注册为工具，由 `ToolCallingManager` 自动编排调用。

---

## 五、 项目迭代与实施路线图 (从 0 到 100)

为确保项目高质量交付，建议分为四个 Phase 稳步推进：

### Phase 1: 基础设施与单链路 MVP (第 1-3 周)

* **目标**：跑通“文档上传 -> 基础切分 -> 向量入库 -> 单路 RAG 问答”闭环。
* **技术动作**：
    * 搭建 Spring Boot 4.1 + Spring AI 2.0 骨架。
    * 部署 PostgreSQL、Milvus、Redis。
    * 实现基础的 `TikaDocumentReader` + `TokenTextSplitter`。
    * 使用 Spring AI 内置的 `QuestionAnswerAdvisor` 跑通基础 RAG。
* **交付物**：后端基础 API，前端简易对话界面。

### Phase 2: 核心 RAG 引擎升级 (第 4-7 周) 【攻坚期】

* **目标**：解决复杂文档解析与专有名词检索痛点。
* **技术动作**：
    * 研发 `SmartOcrRoutingReader`，接入外部 OCR API。
    * 研发 `HtmlProtectingSplitter`，实现表格/图片保护。
    * 引入 Elasticsearch，实现 Milvus + BM25 双路检索与 RRF 融合算法。
    * 实现 PG 与 Milvus 的主数据/向量解耦存储。
* **交付物**：高可用知识入库管道，混合检索调试台。

### Phase 3: Agent 编排与企业级特性 (第 8-11 周)

* **目标**：实现带溯源的流式 Agent 对话与企业级安全管控。
* **技术动作**：
    * 研发 `PrefetchRagAdvisor`，实现上下文证据注入与溯源元数据透传。
    * 实现基于 SSE 的流式事件推送（Token + Trace 卡片）。
    * 引入 `MessageChatMemoryAdvisor` 实现多轮会话管理。
    * 增加 `RateLimitAdvisor` 实现流量控制。
    * 增加多租户隔离、权限管控与输入输出脱敏护栏。
* **交付物**：完整的企业知识库 Agent 工作台（前端 Vue3 全功能上线）。

### Phase 4: 运维、观测与持续优化 (第 12-14 周)

* **目标**：打造可度量、可迭代的 AI 运营闭环。
* **技术动作**：
    * 完善 Chunk CRUD 与索引重建机制。
    * 接入 OpenTelemetry，配置 Grafana AI 监控大盘。
    * 实现问答日志落库与反馈闭环，开发 Bad Case 标注与 Prompt 调优工作流。
    * 引入 `@Tool` 扩展 Agent 业务能力（对接内部 OA/ERP）。
* **交付物**：生产级 release，运维手册，监控大盘。

---

## 六、 企业级避坑指南 (Java/Spring AI 专属)

1. **虚拟线程的陷阱**：Spring AI 2.0 的流式调用 (`Flux`) 和外部 HTTP 客户端（如调用 OCR API）必须确保底层客户端支持虚拟线程（如使用 Spring Boot 4 默认的 RestClient 或 WebClient），否则会导致虚拟线程 Pinning（退化到平台线程），丧失高并发优势。
2. **Milvus 连接池管理**：Spring AI 的 `MilvusVectorStore` 封装了底层 SDK。在企业级高并发下，务必在配置中调优 Milvus Client 的连接池参数，避免检索时出现连接耗尽。
3. **长文本 Token 溢出**：在 `PrefetchRagAdvisor` 中注入证据时，必须计算 Evidence 的 Token 数量。如果检索出的 Chunk 过多，需使用 Spring AI 的上下文压缩机制（或调用轻量级模型）进行压缩，防止超出 LLM 的 Context Window。
4. **SSE 代理超时**：企业级 Nginx/Gateway 默认会对长连接有超时限制（如 60s）。Agent 思考或复杂工具调用可能超过此时间。必须在网关层配置 SSE 心跳保活机制，或调整 Gateway 的 `proxy_read_timeout`。
5. **Advisor 顺序问题**：Advisor 的执行顺序由 `getOrder()` 决定。务必确保 `RateLimit` (限流) 在最外层，`Memory` (记忆) 在 `RAG` (检索) 之前（因为可能需要结合历史对话改写 Query），`Audit` (审计) 在最内层或最外层以捕获完整上下文。

---

## 七、 总结

本项目通过引入 **Spring AI 2.0**，彻底颠覆了传统 Python 体系下 RAG 链路“面条式”的代码结构。

借助 **Advisor 拦截器链**，我们将 Prefetch 检索、记忆管理、安全审计像“搭积木”一样优雅编排；借助 **Java 21 虚拟线程**，我们零成本解决了混合检索与 OCR 调用的 I/O 瓶颈；借助 **主数据与向量解耦设计**，我们赋予了知识库真正的“可运维、可编辑”能力。

这套方案不仅 100% 覆盖了原 Python 项目的所有业务痛点，更在**高可用性、企业级安全、全链路可观测性**上达到了 2026 年企业级 AI 应用的顶尖标准。按照上述四阶段路线图稳步推进，您将交付一个极具市场竞争力的企业级 RAG Agent 工作台。



