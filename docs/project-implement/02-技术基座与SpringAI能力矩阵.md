# 第二章：技术基座与 Spring AI 2.0 能力矩阵

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第一卷「项目全景蓝图（战略层）」第二章）
>
> [📑 返回目录](./README.md) · 最后更新：2026-10-20 · **v2.1 现状对齐**（模型双形态 / 三链 13 Advisor / Neo4j 图库 / 辅助模型族 / 版本基线）
>
> **v2 修订**：决策 2 中删除了关于 `spring.ai.vectorstore.type=custom` 的无效配置说明；决策 3 追加了关于百炼 DashScope 官方 starter 的技术债务注记；2.1 技术选型表修正 Spring Boot 版本描述；决策 4 Advisor 链表中 `PrefetchRagAdvisor` 已按模块化 RAG 规范更名为 `RetrievalAugmentationAdvisor`。
>
> **v2.1 修订（2026-10-20）**：按落地现状重写 2.1 选型表与决策 3/4——主答改为 GLM-5.3-Flash（缺省）/ DeepSeek V4.1（回落）双形态，embedding/rerank 固定百炼，新增辅助族 qwen3.8-flash 与 Neo4j 5.26；Advisor 链更新为三链 13 个 order（移除未存在的 AuthAdvisor，鉴权由 SecurityConfig/JwtUtils 在入口完成）。

### 2.1 技术选型全景（2026-10 现状）

| 层级 | 技术选型 | 版本 | 选型理由 |
|------|---------|------|---------|
| **语言** | Java | 21 LTS | 虚拟线程、Record、Pattern Matching；父 POM 启用 `--enable-preview` |
| **框架** | Spring Boot | 4.1.0 | Spring AI 2.0 GA 官方基线；Boot 4.x Jackson 3（`tools.jackson`） |
| **AI 核心** | Spring AI | 2.0.1 GA | ChatClient / Advisor / 模块化 RAG / MCP；BOM 统一管理 |
| **主答模型** | GLM-5.3-Flash（缺省）/ DeepSeek-V4.1-Flash（回落） | 双形态 `rag.routing.primary.provider=glm\|deepseek` | 一切即切；GLM 强制思考 + effort low，DeepSeek 回落缺省关思考 |
| **备用模型** | 阿里云百炼 qwen3.8-flash | OpenAI 兼容 | 熔断三态接管；路由/改写/L2 二判/Judge/图抽取/语境增强同族 |
| **Embedding** | 阿里云百炼 qwen3.7-text-embedding | 1024 维 | OpenAI 兼容 API，中文语义；与向量库/缓存维度同源 |
| **Rerank** | 阿里云百炼 qwen3.7-text-rerank | REST API | 与 Embedding 同生态、免 GPU；故障降级截断 |
| **向量数据库** | Milvus 2.6+ / pgvector（PG18 扩展） | 双后端 | `kb.vector-store.provider` 切换；小规模 pgvector、大规模 Milvus |
| **全文检索** | Elasticsearch | 9.4.2（ECS 服务端） | ik 中文 BM25 + 高亮 + DSL；与向量路 RRF 融合 |
| **关系数据库** | PostgreSQL | 18 | 事实源（文档/Chunk/会话/审计/反馈/词表/迁移） |
| **图数据库** | Neo4j Community | 5.26（第二台 ECS） | GraphRAG 实体/关系/Chunk 锚点；`rag.graph.enabled` 门控 |
| **缓存** | Redis | 8（内建搜索引擎） | 会话记忆、限流、Token 账本、审批、语义缓存、任务表 |
| **对象存储** | MinIO | S3 兼容 | 原件存储 |
| **可观测** | OpenTelemetry + Micrometer + Prometheus + Grafana + Langfuse/Jaeger | — | 指标/面板/告警/LLM trace 合树 |
| **前端** | Vue 3 + TypeScript + Element Plus + Pinia + Vite 6 | — | 工作台六页（Login/Chat/Documents/Debug/Chunks/Admin） |
| **压测** | Gatling | 3.15 Java DSL | kb-loadtest 独立模块，`gatling:test` 显式触发 |

### 2.2 Spring AI 2.0 核心能力矩阵

#### 2.2.1 五大核心模块

```
spring-ai-commons           ← 领域模型 (Message, Prompt, Media)，零外部依赖
    ↓
spring-ai-model             ← 模型抽象 (ChatModel, EmbeddingModel, ToolCallback)
    ↓
spring-ai-client-chat       ← ChatClient Fluent API + Advisor 链
    ↓
spring-ai-vector-store      ← 存储抽象 (VectorStore, Document, SearchRequest)
    ↓
spring-ai-advisors-vector-store ← Advisor 桥接 (QuestionAnswerAdvisor)
```

#### 2.2.2 本项目用到的核心能力映射

| Spring AI 2.0 能力 | 本项目对应模块 | 使用方式 |
|-------------------|-------------|---------|
| **ChatClient Fluent API** | 三链路对话 | Builder 模式构建；`ragAgentChatClient` / `toolAgentChatClient` / `orchestratorChatClient` |
| **Advisor 拦截器链** | 全部对话链路 | 三链共享安全/配额/记忆 Advisor，链序 13 个 order（见决策 4） |
| **@Tool 注解 + ToolCallingAdvisor** | tool/agent 链 | `EnterpriseMockReadTools` / `EnterpriseMockWriteTools`；自建 `ToolCallingAdvisor(1000)` |
| **手工 ChatModel 装配** | 主答/备用 | `SmartRoutingChatModel`（@Primary）包 primary（GLM/DeepSeek OpenAI 兼容）+ fallback（qwen3.8-flash）；`spring.ai.model.chat=none` 防御位 |
| **VectorStore 抽象** | 混合检索 | PgVectorStore / MilvusVectorStore 条件装配，`kb.vector-store.provider` 切换 |
| **EmbeddingModel** | ETL 向量化 | 百炼 OpenAI 兼容接口，qwen3.7-text-embedding（1024 维） |
| **Document + Reader/Transformer** | ETL 管道 | TikaDocumentReader + SmartParsingRouter + HtmlProtectingSplitter + ContextualEnrichmentTransformer |
| **结构化输出 (.entity())** | 评估/图抽取 | JudgeScore / 实体关系抽取 record；`TolerantJudgeScoreConverter` 剥壳容错 |
| **Flux 流式响应** | SSE 推送 | TOKEN/TRACE/TOOL_CALL/REPLACE/PROGRESS/DONE 帧族 |
| **MCP Server** | 外部工具接入 | `spring-ai-starter-mcp-server-webmvc` Streamable HTTP `/mcp`，三工具 + 身份守卫 |
| **Micrometer 观测** | 全链路监控 | `AiBusinessMetrics` 80 个 meter + OTel bridge；trace 合树 |
| **ChatMemory** | 多轮对话 | `MessageWindowChatMemory` + `RedisChatMemoryRepository`（显式装配，窗口 20 条） |
| **Neo4j 驱动** | GraphRAG | 原生 `neo4j-java-driver` 手工装配，受 `rag.graph.enabled` 门控 |
| **Redisson RSearch** | 语义缓存 | Redis 8 内建搜索引擎 KNN，租户索引域 + 文档 TAG 失效 |

### 2.3 关键技术决策

#### 决策 1：为什么用 Spring AI 2.0 而非 LangChain4j？

| 维度 | Spring AI 2.0 | LangChain4j |
|------|--------------|-------------|
| **Spring 生态整合** | 深度绑定（Boot/Data/Security/Actuator） | 较浅 |
| **Advisor 机制** | 原生 AOP 风格拦截器链，完美契合企业需求 | 无等价机制 |
| **工具调用** | ToolCallingAdvisor + MCP 原生 SDK 2.0 | @Tool 注解为主 |
| **可观测性** | 原生 Micrometer/OTel 集成 | 需手动集成 |
| **社区与维护** | VMware/Tanzu 官方维护 | 社区驱动 |

#### 决策 2：向量库双后端策略 — pgvector + Milvus 可切换

项目同时集成 **pgvector**（PostgreSQL 向量扩展）和 **Milvus**（分布式向量数据库），通过配置开关 `kb.vector-store.provider` 让用户按场景选择。

```
kb.vector-store.provider = pgvector | milvus

┌────────────────────────────────────────────────────────┐
│                    VectorStore 接口                    │
│          (Spring AI 统一抽象，上层无感知)              │
└────────────┬──────────────────────────┬────────────────┘
             │                          │
    ┌────────▼────────┐        ┌────────▼────────┐
    │  PgVectorStore  │        │MilvusVectorStore│
    │  (小规模场景)   │        │  (大规模场景)   │
    ├─────────────────┤        ├─────────────────┤
    │ 基于 PG 原生    │        │ 独立 Milvus 服务│
    │ pgvector 扩展   │        │ 分布式 HNSW 索引│
    │ 零运维增量      │        │ 十亿级向量规模  │
    │ SQL 级过滤      │        │ GPU 加速检索    │
    └────────┬────────┘        └────────┬────────┘
             │                          │
    ┌────────▼────────┐        ┌────────▼────────┐
    │  PostgreSQL 18  │        │   Milvus 2.6+   │
    │  + pgvector     │        │   + etcd        │
    └─────────────────┘        └─────────────────┘
```

**选型建议**：

| 场景 | 推荐后端 | 理由 |
|------|---------|------|
| Chunk < 10万，开发/测试环境 | pgvector | 复用已有 PG，零额外运维 |
| Chunk 10万-100万 | pgvector 或 Milvus | pgvector 调优后仍可胜任，Milvus 性能更佳 |
| Chunk > 100万 | Milvus | 分布式架构，查询延迟稳定 |
| 需要 GPU 加速 | Milvus | 原生 GPU 索引支持 |

**实现方式**：

```java
// kb-infrastructure/vectorstore/VectorStoreConfig.java
@Configuration
@EnableConfigurationProperties(KbVectorStoreProperties.class)
public class VectorStoreConfig {

    @Bean
    @ConditionalOnProperty(prefix = "kb.vector-store", name = "provider",
        havingValue = "pgvector", matchIfMissing = false)
    public VectorStore pgvectorVectorStore(
        JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ...) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "kb.vector-store", name = "provider",
        havingValue = "milvus", matchIfMissing = true)
    public VectorStore milvusVectorStore(
        MilvusServiceClient milvusClient, EmbeddingModel embeddingModel, ...) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel).build();
    }
}
```

**关键设计**：
- 上层模块（kb-ai-core、kb-etl）注入 `VectorStore` 接口，完全不感知底层实现
- `kb.vector-store.provider` 默认 `milvus`，保证向后兼容
- 通过自定义 `VectorStore` @Bean 使 Spring AI auto-config 的 `@ConditionalOnMissingBean` 退让（`spring.ai.vectorstore.type=custom` 系无效配置，v1 文档有误，已删除）
- 切换只需一行配置或环境变量：`export KB_VECTOR_STORE_PROVIDER=pgvector`

#### 决策 2b：PostgreSQL 双库解耦（pgvector 模式下天然解耦）

**pgvector 模式**下，向量与主数据共享同一个 PostgreSQL 实例，但逻辑分离：

```
 PostgreSQL (主数据 + 向量)
┌──────────────────────────────┐
│  kb_document                 │
│  kb_section                  │
│  kb_chunk (主数据)           │
│  kb_session / kb_message     │
│  kb_audit_log / kb_feedback  │
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ │
│  kb_embeddings (pgvector)    │  ← 向量表，独立存储
│    .id / .embedding(1024)    │
│    .chunk_id (关联 kb_chunk) │
│    .metadata (JSONB)         │
└──────────────────────────────┘
```

**Milvus 模式**下，沿用原有的 PG-Milvus 双库解耦架构：

```
 PostgreSQL (主数据)           Milvus (向量副本)
┌──────────────────┐         ┌───────────────────┐
│ kb_document      │         │ kb_chunks         │
│ kb_section       │◄────────│  .vector_id       │
│ kb_chunk (主数据)│ 外键关联│  .embedding       │
│ kb_session       │         │  .doc_id (标量)   │
│ kb_message       │         │  .tenant_id (标量)│
│ kb_audit_log     │         │  .is_deleted      │
│ kb_feedback      │         │  .chunk_type      │
└──────────────────┘         └───────────────────┘
```

**两种模式的共同优势**：
- Chunk 可在 PG 中随意编辑，只需异步更新向量
- 事务一致性由 PG 保证
- 支持 Chunk 级局部向量更新（delete + add），无需重建整个文档

#### 决策 3：国产模型多形态 — GLM-5.3-Flash 主答 + DeepSeek 回落 + 百炼辅助族

项目采用 **国产模型 API**，不依赖本地 GPU 部署；主答形态由一个环境变量切换，检索/改写/Judge 等轻任务恒挂辅助族，避免主模型切换引起检索形态漂移：

```
rag.routing.primary.provider=glm（缺省）  →  GLM-5.3-Flash（强制思考，effort=low，temperature=0.2）
rag.routing.primary.provider=deepseek     →  DeepSeek-V4.1-Flash（缺省关思考）
                  ↓ 失败即切（熔断三态）
fallbackChatModel                          →  百炼 qwen3.8-flash（enable_thinking=false）
Embedding / Rerank                         →  百炼 qwen3.7-text-embedding / qwen3.7-text-rerank
路由改写 / L2 二判 / Judge / 图抽取 / 语境增强 →  qwen3.8-flash
```

**配置示例**（`kb-ai-core/src/main/resources/application-ai.yml`，经 `spring.config.import` 导入）：

```yaml
spring:
  ai:
    model:
      chat: none                 # 主模型手工装配；防未来误引 chat starter
      embedding: openai
    openai:
      api-key: ${DASHSCOPE_API_KEY:}
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      embedding:
        model: ${DASHSCOPE_EMBEDDING_MODEL:qwen3.7-text-embedding}

rag:
  routing:
    primary:
      provider: ${RAG_ROUTING_PRIMARY_PROVIDER:glm}
      glm:
        api-key: ${ZHIPU_API_KEY:}
        model: ${ZHIPU_MODEL:glm-5.3-flash}
        temperature: ${ZHIPU_TEMPERATURE:0.2}
        reasoning-effort: ${ZHIPU_REASONING_EFFORT:low}
      deepseek:
        api-key: ${DEEPSEEK_API_KEY:}
        model: ${DEEPSEEK_MODEL:deepseek-flash}
        thinking-type: ${DEEPSEEK_THINKING_TYPE:disabled}
    fallback:
      enabled: ${RAG_ROUTING_FALLBACK_ENABLED:true}
      model: ${RAG_ROUTING_FALLBACK_MODEL:qwen3.8-flash}
      enable-thinking: ${RAG_ROUTING_FALLBACK_ENABLE_THINKING:false}
```

**选型理由**：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 主答 | GLM-5.3-Flash（缺省） | 生产门禁基线 md1-final-3（CA 0.818 / HR 5.3%）；思考 + effort low 平衡质量与 TTFT；temperature 0.2 经消融定档 |
| 回落 | DeepSeek-V4.1-Flash | 国产开源标杆、成本低；思考模式默认开且采样参数被静默忽略，故回落形态显式关思考 |
| 备用/辅助 | 百炼 qwen3.8-flash | 与主答跨厂商（避免 self-preference）；关闭思考后单调用延迟可控；Embedding/Rerank 同生态免 GPU |
| Embedding | qwen3.7-text-embedding | 1024 维，中文语义；与图向量/缓存维度三处同源 |
| Rerank | qwen3.7-text-rerank | 与 Embedding 同生态；REST 超时降级截断 |

> **v2.1 注**：v2 草图的 `spring-ai-starter-model-deepseek` 原生集成已退役（流式 usage 不满足配额计账）；主模型统一手工装配为 OpenAI 兼容形态并开启 `stream_options.include_usage`。百炼仍走 OpenAI 兼容模式（官方 `spring-ai-starter-model-alibaba` 保留为可选，实际未启用）。

**环境变量**（最小集）：

```bash
export ZHIPU_API_KEY=sk-xxx         # GLM 主答（缺省形态）
export DEEPSEEK_API_KEY=sk-xxx      # 可选：回落形态主答
export DASHSCOPE_API_KEY=sk-xxx     # Embedding / Rerank / 备用与辅助族
```

#### 决策 4：Advisor 链设计原则（三链 13 个 order）

Advisor 的执行顺序由 `getOrder()` 决定（Order 越小越外层：before 先执行、after 后执行）。**鉴权不在 Advisor 链内**——`SecurityConfig` + `JwtUtils` 在 Controller 入口完成 JWT/租户校验；链内按「审计 → 预算 → 限流 → 输出护栏 → 输入消毒 → L2 → 记忆/路由/缓存/检索门控 → 工具调用」排布：

| Order | Advisor | rag 链 | tool 链 | agent 链 | 职责 |
|---|---|---|---|---|---|
| 10 | `AuditTraceAdvisor` | ✅ | ✅ | ✅ | 全链路审计（含被拒/错误），异步旁路落库 |
| 30 | `TokenBudgetAdvisor` | ✅ | ✅ | ✅ | 租户日 Token 账本 |
| 100 | `RateLimitAdvisor` | ✅ | ✅ | ✅ | 租户令牌桶（429） |
| 110 | `OutputGuardrailAdvisor` | ✅ | ✅ | ✅ | 输出词表/金丝雀；流式增量放行 + REPLACE 追回 |
| 300 | `InputSanitizeAdvisor` | ✅ | ✅ | ✅ | 归一化 + PII 七类掩码 + 注入拦截 |
| 320 | `SemanticInjectionAdvisor` | ✅ | ✅ | ✅ | L2 语义二判（前提 = L1 同链先执行） |
| 400 | `MessageChatMemoryAdvisor` | ✅ | ✅ | ✅（可关） | 多轮记忆（Redis，窗口 20） |
| 420 | `TaskBoundaryAdvisor` | — | — | ✅ | 编排链任务边界注记（多轮历史分隔） |
| 440 | `QueryRoutingAdvisor` | ✅ | — | — | 意图分类 + 查询改写预写（skipRetrieval） |
| 450 | `RetrievalTraceAdvisor` | ✅ | — | — | 检索上下文/trace 透传 |
| 460 | `CacheCheckAdvisor` | ✅（条件） | — | — | 语义缓存命中重放 / 未命中流末写入 |
| 500 | `RetrievalAugmentationAdvisor` | ✅ | — | — | 查询改写 + 多路检索 + RRF + 重排 + 证据注入 |
| 1000 | `ToolCallingAdvisor` | — | ✅ | ✅ | 工具调用循环（tool/agent 共享） |

> 链序完整表与装配位置见第十一章 11.2 / §11.5.5；`CacheCheckAdvisor`、`SemanticInjectionAdvisor` 等条件件在关闭态整体缺位，链形态逐字节不变。
