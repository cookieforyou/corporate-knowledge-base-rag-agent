# 企业知识库 RAG Agent 工作台：Spring AI 2.0 落地全景分析报告

> **项目定位**：基于 Spring AI 2.0 重构的企业级知识管理与智能问答平台
> 
> **技术基座**：Java 21 + Spring Boot 4.1 + Spring AI 2.0.0 GA + Milvus + PostgreSQL + Vue3
> 
> **报告目标**：提供从架构设计到上线运维的全生命周期、高可操作性落地指南

---

## 一、 项目基本信息

| 维度 | 说明 |
|---|---|
| **项目名称** | 企业知识库 RAG Agent 工作台 & 知识库助手 |
| **业务痛点** | 文档格式复杂解析难、纯向量检索命中率低、回答无来源溯源、链路割裂缺乏观测 |
| **原技术栈** | Python + FastAPI + LangChain/LangGraph + Vue3 |
| **新技术栈** | **Java 21 + Spring Boot 4.1 + Spring AI 2.0 + Milvus + PostgreSQL + Redis + Vue3** |
| **核心价值** | 将 Python 脚本式的 AI 链路，升级为**高可用、可观测、易维护、企业级**的 Java 工程化架构 |

---

## 二、 项目全局概览与战略定位

### 2.1 战略定位

本项目不仅是一个"聊天机器人"，而是一个**企业级知识资产管理与智能检索平台**。

- **对业务**：提供带来源溯源的精准问答，降低知识获取成本。
- **对运维**：提供从文档解析、Chunk 观测到检索调试的全链路可视化工作台。
- **对技术**：沉淀一套基于 Spring AI 2.0 的企业级 RAG 标准组件库，可复用于其他 AI 项目。

### 2.2 Spring AI 2.0 带来的范式转变

原 Python 方案中，文档解析、切分、检索、Agent 编排多依赖 LangChain 的链式调用，调试困难且与业务系统耦合深。引入 Spring AI 2.0 后：

- **ETL 管道化**：使用 `DocumentReader` + `DocumentTransformer` + `DocumentWriter` 标准接口重构解析链路。
- **Advisor 切面化**：将 RAG 检索、日志审计、安全护栏通过 `Advisor` 链解耦，业务代码零侵入。
- **Agent 标准化**：使用 `ToolCallingManager` 和 `@Tool` 注解替代 LangGraph 的复杂图编排，降低心智负担。

---

## 三、 业务需求与核心功能详细清单

### 3.1 业务需求清单 (Business Requirements)

1. **多源异构文档入库**：支持 PDF、Docx、Markdown、TXT，特别是复杂表格和扫描件的稳定解析。
2. **高精度知识检索**：解决纯向量检索对"标题词、字段名、课程名"命中不准的问题。
3. **可解释的智能问答**：回答必须附带来源依据（Chunk ID、文件名、页码、得分），杜绝幻觉。
4. **全链路可视化观测**：前端工作台需能查看文档状态、Chunk 内容、检索打分和对话历史。
5. **知识库持续运维**：支持 Chunk 级编辑/删除、文档级物理删除、索引一键重建。

### 3.2 核心功能清单 (Functional Requirements)

| 模块 | 核心功能点 | Spring AI 2.0 对应技术组件 |
|---|---|--- |
| **文档解析引擎** | 原生解析 + OCR 智能路由、半结构化保护式切分 | `TikaDocumentReader`, 自定义 `DocumentTransformer` |
| **混合检索引擎** | Milvus 向量检索 + 应用层 BM25 + RRF 融合排序 | `VectorStore` (Milvus), 自定义 `HybridSearchAdvisor` |
| **Agent 问答引擎** | Prefetch 预检索、多轮对话、流式回答、工具调用 | `ChatClient`, `QuestionAnswerAdvisor`, `@Tool`, SSE |
| **溯源与观测** | 证据注入、来源评分回传、问答日志落库 | 自定义 `GroundingAdvisor`, `AuditLogAdvisor` |
| **知识库运维** | Chunk 编辑/删除、索引重建、会话管理 | Spring Data JPA, `VectorStore` 管理 API |

---

## 四、 系统架构设计

### 4.1 总体架构图 (四层架构)

```text
┌─────────────────────────── 接入层 (Vue3 工作台 + Spring Cloud Gateway) ─────────────────────────────┐
│  文档管理台  │  Chunk 观测台  │  检索调试台  │  Agent 对话窗 (SSE)  │  运维与审计看板                     │
└──────────────────────────────────────┬────────────────────────────────────────────────────────────┘
                                       │ REST / SSE
┌──────────────────────────────────────▼────────────────────────────────────────────────────────────┐
│                              AI 编排层 (Spring AI 2.0 Orchestration)                               │
│  ┌──────────────┐  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │  ChatClient  │→ │                       Advisor Chain (拦截器链)                            │   │
│  │  (Fluent API)│  │ [Auth] → [RateLimit] → [HybridSearch(RAG)] → [Grounding] → [AuditLog]    │   │
│  └──────┬───────┘  └──────────────────────────────────────────────────────────────────────────┘   │
│         │                                     │                                                   │
│  ┌──────▼───────┐                      ┌──────▼───────┐                                           │
│  │ToolCalling   │                      │ Prompt Mgr   │  (Prompt 模板版本化管理)                    │
│  │Manager       │                      └──────────────┘                                           │
│  │(@Tool/MCP)   │                                                                                 │
│  └──────────────┘                                                                                 │
├───────────────────────────────────────────────────────────────────────────────────────────────────┤
│                              能力层 (Capability & ETL)                                             │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐   │
│  │ Document ETL Pipe  │  │ Hybrid Retriever   │  │ Rerank Model       │  │ OCR Router         │   │
│  │(Tika+自定义Transformer)│ (Vector+BM25+RRF)  │  │(Cross-Encoder)     │  │(Tesseract/Cloud)   │   │
│  └────────────────────┘  └────────────────────┘  └────────────────────┘  └────────────────────┘   │
├───────────────────────────────────────────────────────────────────────────────────────────────────┤
│                              基础设施层 (Infrastructure)                                            │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────────────────┐     │
│  │ Milvus   │  │PostgreSQL│  │  Redis   │  │ OpenAI/  │  │ Embedding│  │ OpenTelemetry/     │     │
│  │(VectorDB)│  │(业务主库) │  │(缓存/限流) │  │ Ollama   │  │  Model   │  │ Prometheus/Grafana │     │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └──────────┘  └────────────────────┘     │
└───────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 核心设计决策

1. **为什么不用 LangChain4j 而用 Spring AI 2.0？**
  - Spring AI 2.0 与 Spring 生态（Boot, Data, Security, Actuator）深度绑定，`Advisor` 机制完美契合企业级 AOP 需求，且 `ToolCallingManager` 对 MCP 和 `@Tool` 的支持更符合 Java 开发者的依赖注入习惯。
2. **为什么采用 Milvus + PostgreSQL 双库设计？**
  - Milvus 专注高维向量检索（亿级规模）；PostgreSQL 存储文档元数据、Chunk 文本、用户会话和业务关系，保证事务一致性和复杂关系查询。

---

## 五、 数据库与数据架构设计

### 5.1 核心数据模型 (PostgreSQL)

采用 **Document -> Section -> Chunk** 的三层解耦设计，便于局部重建和 Chunk 级运维。

```sql
-- 1. 文档主表 (Document)
CREATE TABLE kb_document (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50),           -- PDF, DOCX, MD
    size BIGINT,
    status VARCHAR(20),         -- PARSING, SUCCESS, FAILED
    parse_route VARCHAR(20),    -- NATIVE, OCR
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 2. 切分块表 (Chunk) - 核心业务表
CREATE TABLE kb_chunk (
    id VARCHAR(36) PRIMARY KEY,
    doc_id VARCHAR(36) REFERENCES kb_document(id) ON DELETE CASCADE,
    chunk_index INT,            -- 在文档中的顺序
    content TEXT NOT NULL,      -- 切分后的文本（支持人工编辑）
    metadata JSONB,             -- 页码、标题、表格标记等
    token_count INT,
    vector_id VARCHAR(100),     -- 对应 Milvus 中的向量 ID
    is_deleted BOOLEAN DEFAULT FALSE, -- 软删除标记
    created_at TIMESTAMP
);

-- 3. 对话与会话表 (Conversation & Message)
CREATE TABLE kb_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(50),
    title VARCHAR(255),
    created_at TIMESTAMP
);

CREATE TABLE kb_message (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) REFERENCES kb_session(id),
    role VARCHAR(20),           -- USER, ASSISTANT
    content TEXT,
    references JSONB,           -- 溯源引用的 chunk_id 列表及得分
    token_usage JSONB,          -- prompt_tokens, completion_tokens
    created_at TIMESTAMP
);

-- 4. 审计与检索日志表 (Audit Log)
CREATE TABLE kb_audit_log (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36),
    query_text TEXT,
    rewritten_query TEXT,
    retrieved_chunks JSONB,     -- 召回的 chunk 详情及各项得分
    final_answer TEXT,
    latency_ms INT,
    created_at TIMESTAMP
);
```

### 5.2 向量存储设计 (Milvus)

- **Collection Name**: `kb_chunks`
- **Fields**:
    - `vector_id` (VARCHAR, Primary Key)
    - `embedding` (FLOAT_VECTOR, dim=1024, 根据 Embedding 模型定)
    - `doc_id` (VARCHAR, 用于标量过滤)
    - `is_deleted` (BOOL, 配合 PG 软删除)
- **Index**: HNSW (适合高召回率要求)

---

## 六、 核心功能实现方案 (Spring AI 2.0 深度实战)

### 6.1 文档解析与 ETL 管道 (双链路 + 保护式切分)

**痛点解决**：复杂 PDF 表格解析碎片化。

**方案**：自定义 `DocumentTransformer` 实现 HTML 标签保护与智能路由。

```java
@Service
public class DocumentEtlService {

    private final VectorStore milvusVectorStore;
    private final KbChunkRepository chunkRepository;
    private final OcrRouterService ocrRouter;

    public void processDocument(MultipartFile file, String docId) {
        // 1. 智能路由：判断是否需要 OCR
        Resource resource = file.getResource();
        DocumentReader reader;
        
        if (ocrRouter.needsOcr(file)) {
            // OCR 链路：调用外部 OCR 服务返回带 <table> <img> 标签的 HTML
            String htmlContent = ocrRouter.extract(file);
            reader = new StringReader(htmlContent); 
        } else {
            // 原生链路：使用 Tika 解析
            reader = new TikaDocumentReader(resource);
        }

        // 2. 读取文档
        List<Document> rawDocs = reader.get();

        // 3. 保护式切分 (自定义 Transformer)
        ProtectedTokenTextSplitter splitter = new ProtectedTokenTextSplitter(
            800, 200, 5, 10000, true, List.of("<table>", "<img>")
        );
        List<Document> chunks = splitter.apply(rawDocs);

        // 4. 持久化到 PG 并获取 ID
        List<KbChunkEntity> entities = saveChunksToPg(docId, chunks);
        
        // 5. 向量化并写入 Milvus
        List<Document> vectorDocs = entities.stream()
            .map(e -> new Document(e.getContent(), Map.of("doc_id", docId, "chunk_id", e.getId())))
            .toList();
            
        milvusVectorStore.add(vectorDocs);
        
        // 6. 更新 Milvus Vector ID 到 PG
        updateVectorIds(entities, vectorDocs);
    }
}
```

### 6.2 混合检索与 RRF 融合排序 (Hybrid Search)

**痛点解决**：纯向量检索对专有名词、字段名命中差。

**方案**：自定义 `HybridSearchAdvisor`，并行执行 Milvus 向量检索和 PG 的 BM25 (或 Elasticsearch) 检索，在应用层进行 RRF (Reciprocal Rank Fusion) 融合。

```java
@Component
public class HybridSearchAdvisor implements CallAroundAdvisor {

    private final VectorStore milvusStore;
    private final KbChunkRepository chunkRepo; // 支持 PG 全文检索或对接 ES
    private final int topK = 5;
    private final int rrfK = 60; // RRF 常数

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String query = request.userText();
        
        // 1. 并行召回
        CompletableFuture<List<ScoredChunk>> vectorFuture = CompletableFuture.supplyAsync(() -> 
            searchVector(query));
        CompletableFuture<List<ScoredChunk>> bm25Future = CompletableFuture.supplyAsync(() -> 
            searchBm25(query));
            
        List<ScoredChunk> vectorResults = vectorFuture.join();
        List<ScoredChunk> bm25Results = bm25Future.join();

        // 2. RRF 融合排序
        List<ScoredChunk> fusedResults = rrfFuse(vectorResults, bm25Results);
        List<ScoredChunk> topResults = fusedResults.subList(0, Math.min(topK, fusedResults.size()));

        // 3. 构建上下文并注入 Prompt
        String context = buildContext(topResults);
        String newUserText = "基于以下参考资料回答问题：\n" + context + "\n\n用户问题：" + query;

        // 4. 将溯源信息存入 AdviseContext，供后续 GroundingAdvisor 使用
        Map<String, Object> adviseContext = new HashMap<>(request.adviseContext());
        adviseContext.put("retrieved_chunks", topResults);

        AdvisedRequest modifiedRequest = AdvisedRequest.from(request)
                .withUserText(newUserText)
                .withAdviseContext(adviseContext)
                .build();

        return chain.nextAroundCall(modifiedRequest);
    }

    private List<ScoredChunk> rrfFuse(List<ScoredChunk> v, List<ScoredChunk> b) {
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < v.size(); i++) {
            scores.merge(v.get(i).chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < b.size(); i++) {
            scores.merge(b.get(i).chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
        }
        // 排序并返回
        // ...
    }
    
    @Override public int getOrder() { return 500; } // 在记忆之后，模型调用之前
}
```

### 6.3 Agent 问答与流式输出 (SSE + Prefetch)

**痛点解决**：Agent 绕开知识库直接生成，流式输出时来源溯源丢失。

**方案**：使用 `ChatClient` 结合 `Flux` 流式输出，通过 `Prefetch` 机制确保检索先于生成，并在流结束时通过 SSE 事件推送引用来源。

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ChatClient chatClient;

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(
            @RequestParam String sessionId, 
            @RequestParam String query) {

        return chatClient.prompt()
                .user(query)
                .advisors(spec -> spec
                    .param("chat_memory_conversation_id", sessionId)
                    .param("user_id", SecurityContextHolder.getContext().getAuthentication().getName()))
                .stream()
                .chatResponse()
                .map(response -> {
                    // 处理流式文本块
                    String text = response.getResult().getOutput().getContent();
                    return ServerSentEvent.<ChatStreamEvent>builder()
                            .event("message")
                            .data(new ChatStreamEvent("text", text))
                            .build();
                })
                .concatWith(Mono.just(buildReferenceEvent(sessionId))); // 流结束时推送溯源引用
    }

    private ServerSentEvent<ChatStreamEvent> buildReferenceEvent(String sessionId) {
        // 从 Redis 或 ThreadLocal 中获取 HybridSearchAdvisor 存入的 retrieved_chunks
        List<ScoredChunk> chunks = RetrievalContext.getChunks();
        return ServerSentEvent.<ChatStreamEvent>builder()
                .event("references")
                .data(new ChatStreamEvent("references", chunks))
                .build();
    }
}
```

### 6.4 来源溯源与证据可解释性 (Grounding)

**方案**：自定义 `GroundingAdvisor`，在模型生成完成后，将引用的 Chunk 元数据（文件名、页码、得分）格式化并附加到最终响应中，同时落库审计。

```java
@Component
public class GroundingAdvisor implements CallAroundAdvisor {

    private final AuditLogRepository auditLogRepository;

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        AdvisedResponse response = chain.nextAroundCall(request);
        
        List<ScoredChunk> chunks = (List<ScoredChunk>) request.adviseContext().get("retrieved_chunks");
        String originalOutput = response.response().getResult().getOutput().getContent();
        
        // 1. 生成溯源标记 (例如：[1], [2])
        String groundedOutput = appendCitations(originalOutput, chunks);
        
        // 2. 异步落库审计日志
        saveAuditLog(request, response, chunks);
        
        return AdvisedResponse.from(response)
                .withModifiedOutput(groundedOutput)
                .build();
    }
    
    @Override public int getOrder() { return 100; } // 靠近输出端
}
```

---

## 七、 非功能性需求实现方案

### 7.1 高可用与容灾

- **多模型路由**：使用 `SmartRoutingChatModel`（见前文），配置 OpenAI 为主，Ollama 本地部署模型为 Fallback，防止外部 API 宕机导致服务不可用。
- **异步 ETL**：文档解析和向量化是 CPU/IO 密集型任务，必须通过 `@Async` 或消息队列（RabbitMQ/Kafka）异步处理，避免阻塞 Web 线程。

### 7.2 安全与合规

- **数据脱敏**：实现 `InputSanitizeAdvisor`，在发送给大模型前，正则替换手机号、身份证等 PII 数据。
- **权限控制**：知识库数据隔离。在 `HybridSearchAdvisor` 中，通过 Milvus 的标量过滤（Scalar Filtering）注入 `tenant_id` 或 `dept_id`，确保用户只能检索到有权限的文档。

### 7.3 可观测性 (Observability)

- **全链路追踪**：集成 OpenTelemetry，为每次 ChatClient 调用生成 Trace ID，串联 HTTP 请求 -> Advisor 链 -> Milvus 检索 -> LLM 调用 -> PG 落库的全过程。
- **业务指标**：通过 Micrometer 暴露 `rag.retrieval.latency`（检索耗时）、`rag.hit.rate`（命中率）、`llm.token.usage`（Token 消耗）到 Prometheus + Grafana。

---

## 八、 研发工程化方案

### 8.1 项目模块划分 (Maven Multi-Module)

```text
kb-rag-agent/
├── kb-commons/          # 通用工具、DTO、异常定义
├── kb-domain/           # 领域模型、JPA Entity、Repository
├── kb-etl/              # 文档解析、OCR 路由、切分算法 (独立部署或异步 Worker)
├── kb-ai-core/          # Spring AI 配置、自定义 Advisor、Prompt 管理
├── kb-api/              # REST Controller、SSE 接口、Gateway 配置
└── kb-admin/            # 运维后台接口 (Chunk 编辑、索引重建)
```

### 8.2 Prompt 版本化管理

严禁在代码中硬编码 Prompt。使用数据库或 Nacos 配置中心管理 Prompt 模板，支持 A/B 测试和一键回滚。

```java
@Service
public class PromptService {
    public Prompt getSystemPrompt(String version) {
        String template = configCenter.getConfig("prompt.system." + version);
        return new Prompt(template);
    }
}
```

---

## 九、 部署与运维方案

### 9.1 容器化与 K8s 部署

- **ETL Worker 独立部署**：文档解析消耗大量内存（特别是 Tika 和 OCR），将 `kb-etl` 模块打包为独立的 Deployment，配置较高的 Memory Limit，并与 API 服务隔离，防止 OOM 拖垮主服务。
- **Milvus 集群**：生产环境部署 Milvus 分布式集群（etcd + MinIO + Pulsar + QueryNode），保证向量检索的高可用。

### 9.2 知识库运维工作台功能

- **Chunk 级编辑**：用户在 UI 修改 Chunk 文本后，后端调用 `milvusStore.delete(vectorId)` 然后 `milvusStore.add(newDoc)` 实现局部向量更新，无需重建整个文档。
- **索引重建**：提供 `/api/admin/rebuild-index` 接口，后台起线程读取 PG 中的 Chunk 数据，批量重新向量化并写入 Milvus。

---

## 十、 项目实施步骤与路线 (12周计划)

| 阶段 | 周期 | 核心任务 | 交付物 |
|---|---|---|---|
| **Phase 1: 基础设施与数据链路** | W1-W2 | 搭建 Spring Boot 4 + Spring AI 2.0 骨架；设计 PG/Milvus 表结构；实现基础文档上传与 Tika 解析。 | 可运行的基础工程、数据库 DDL、基础 ETL 管道 |
| **Phase 2: 核心 RAG 引擎** | W3-W5 | 实现双链路解析与保护式切分；实现 Milvus 向量检索；开发 `HybridSearchAdvisor` (BM25+RRF)。 | 高精度混合检索引擎、Chunk 观测 API |
| **Phase 3: Agent 与问答闭环** | W6-W8 | 配置 `ChatClient` 与 Advisor 链；实现 SSE 流式输出；开发 `GroundingAdvisor` 实现来源溯源。 | 带溯源的流式问答 API、多轮对话支持 |
| **Phase 4: 工作台与运维能力** | W9-W10 | 前后端联调；实现 Chunk 编辑/删除、索引重建、问答日志审计看板。 | 完整的 Vue3 工作台、运维管理后台 |
| **Phase 5: 企业级加固与上线** | W11-W12 | 接入 OpenTelemetry 可观测性；压测与性能调优；安全脱敏与权限隔离；生产环境部署。 | 生产级系统、Grafana 监控大盘、上线报告 |

---

## 十一、 质量保障与交付标准

### 11.1 测试策略

1. **ETL 单元测试**：针对各种复杂 PDF（含表格、扫描件）编写断言，验证切分后 `<table>` 标签的完整性。
2. **RAG 评估集 (Eval)**：构建包含 200+ 问答对的黄金数据集（Golden Dataset）。在 CI/CD 中运行自动化评估，计算 **Context Relevance (上下文相关性)** 和 **Faithfulness (忠实度/无幻觉率)**。
3. **集成测试**：使用 Testcontainers 启动 PG 和 Milvus 容器，验证完整的 Advisor 链路和 RRF 融合逻辑。

### 11.2 交付验收标准

- **功能指标**：复杂表格解析可用率 > 90%；混合检索 Top-5 命中率 > 85%；回答必须 100% 携带溯源引用。
- **性能指标**：流式首字响应时间 (TTFT) < 1.5s；10万级 Chunk 库检索耗时 < 200ms。
- **工程指标**：核心链路 OpenTelemetry 追踪覆盖率 100%；Prompt 外部化配置率 100%。

---

## 结语

通过引入 **Spring AI 2.0**，本项目彻底摆脱了 Python 脚本式 AI 开发的"黑盒"与"脆弱"，将企业级 RAG 系统的**工程化、可控性、可观测性**提升到了全新高度。

`Advisor` 机制让 RAG 检索、安全护栏、日志审计像搭积木一样灵活组合；`ToolCallingManager` 和标准化的 `VectorStore` 接口为未来接入更多 MCP 工具和异构数据库留下了充足空间。按照本报告的路径稳步推进，您将高质量交付一个真正具备生产级战斗力的企业知识库 AI 平台。


