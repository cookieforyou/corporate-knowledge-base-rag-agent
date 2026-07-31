# 第二章：技术基座与 Spring AI 2.0 能力矩阵

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第一卷「项目全景蓝图（战略层）」第二章）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31
>
> **v2 修订**：决策 2 中删除了关于 `spring.ai.vectorstore.type=custom` 的无效配置说明；决策 3 追加了关于百炼 DashScope 官方 starter 的技术债务注记；2.1 技术选型表修正 Spring Boot 版本描述；决策 4 Advisor 链表中 `PrefetchRagAdvisor` 已按模块化 RAG 规范更名为 `RetrievalAugmentationAdvisor`。

### 2.1 技术选型全景

| 层级 | 技术选型 | 版本 | 选型理由 |
|------|---------|------|---------|
| **语言** | Java | 21 LTS | 虚拟线程、结构化并发、Record、Pattern Matching |
| **框架** | Spring Boot | 4.1.0 | Spring AI 2.0 GA 官方基线为 4.0，4.1 为兼容后续版本 |
| **AI 核心** | Spring AI | 2.0.0 GA | 2026.6.12 发布，企业级 AI 应用开发平台 |
| **向量数据库** | Milvus / pgvector | 2.6+ / PG18 扩展 | 双后端可切换：小规模用 pgvector（零运维增量），大规模用 Milvus（分布式 HNSW） |
| **全文检索** | Elasticsearch | 8.19 | BM25 关键词检索 + RRF 原生支持 |
| **关系数据库** | PostgreSQL | 18+ | 主数据/元数据存储，事务一致性 |
| **缓存** | Redis | 8.x+ | 会话记忆、限流、Semantic Cache (RediSearch) |
| **对象存储** | MinIO | 最新稳定版 | S3 兼容，文档 OSS 存储 |
| **可观测性** | OpenTelemetry + Micrometer + Prometheus + Grafana | - | 全链路 Trace + 业务指标 |
| **前端** | Vue3 + TypeScript | - | 工作台 UI |
| **LLM** | DeepSeek V4 | deepseek-v4-flash | `spring-ai-starter-model-deepseek` 原生集成，性价比最高 |
| **Embedding** | 阿里云百炼 DashScope | qwen3.7-text-embedding | OpenAI 兼容 API，通过 `spring-ai-starter-model-openai` 对接 |

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
| **ChatClient Fluent API** | Agent 对话 | Builder 模式构建，注入 Advisor 链 |
| **Advisor 拦截器链** | 全部对话链路 | 自定义 9 个 Advisor（RAG/记忆/安全/审计/限流/溯源/预算） |
| **@Tool 注解 + ToolCallingAdvisor** | Agent 工具调用 | 对接 OA/ERP/数据库查询等业务工具 |
| **DeepSeekChatModel** | Agent 对话 | `spring-ai-starter-model-deepseek` 原生集成，替代 OpenAI |
| **VectorStore 抽象** | 混合检索 | PgVectorStore / MilvusVectorStore 实现类，通过配置开关切换 |
| **EmbeddingModel** | ETL 向量化 | DashScope（百炼）OpenAI 兼容接口，模型 qwen3.7-text-embedding |
| **Document + DocumentReader/Transformer** | ETL 管道 | TikaDocumentReader + 自定义 Transformer |
| **结构化输出 (.entity())** | 溯源格式化 | Map 到 AgentResponse record |
| **Flux 流式响应** | SSE 推送 | 流式 Token + Trace 事件推送 |
| **MCP 协议集成** | 外部工具接入 | 原生 SDK 2.0，SSE/Stdio 双模式 |
| **Micrometer 观测** | 全链路监控 | 原生集成，自动采集指标 |
| **ChatMemory** | 多轮对话 | `MessageWindowChatMemory` + `RedisChatMemoryRepository`（v2 修正：无 RedisChatMemory 类） |

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
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│
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

#### 决策 3：国产模型选型 — DeepSeek V4（LLM）+ 阿里云百炼（Embedding）

项目采用 **国产模型 API**，不依赖本地 GPU 部署：

```
spring.ai.model.chat=deepseek     →  DeepSeekChatModel 原生集成
spring.ai.model.embedding=openai  →  OpenAiEmbeddingModel → 百炼 DashScope

┌──────────────────────────────────────────────────────────┐
│                      kb-ai-core                          │
│  ┌─────────────────────┐  ┌───────────────────────────┐  │
│  │ DeepSeekChatModel   │  │  OpenAiEmbeddingModel     │  │
│  │ (spring-ai-starter- │  │  (spring-ai-starter-      │  │
│  │  model-deepseek)    │  │  model-openai)            │  │
│  │                     │  │                           │  │
│  │ model: deepseek-    │  │ base-url: dashscope.      │  │
│  │        v4-flash     │  │  aliyuncs.com             │  │
│  │ temp: 0.1           │  │ model: qwen3.7-text-      │  │
│  │ max-tokens: 4096    │  │  embedding                │  │
│  └─────────┬───────────┘  └───────────┬───────────────┘  │
│            │                          │                  │
└────────────┼──────────────────────────┼──────────────────┘
             │                          │
      ┌──────▼──────┐          ┌────────▼────────────┐
      │ DeepSeek API│          │ 阿里云百炼 DashScope│
      │ api.deepseek│          │ dashscope.aliyuncs  │
      │   .com      │          │   .com              │
      └─────────────┘          └─────────────────────┘
```

**配置示例**（`kb-api/application.yml`）：

```yaml
spring:
  ai:
    model:
      chat: deepseek
      embedding: openai

    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        model: ${DEEPSEEK_MODEL:deepseek-v4-flash}
        temperature: 0.1
        max-tokens: 4096

    openai:
      api-key: ${DASHSCOPE_API_KEY:}
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      embedding:
        model: ${DASHSCOPE_EMBEDDING_MODEL:qwen3.7-text-embedding}
```

**选型理由**：

| 决策点 | 选择 | 理由 |
|--------|------|------|
| LLM | DeepSeek V4 (`deepseek-v4-flash`) | 国产开源标杆，RAG 场景得分 9.8/10；Spring AI 2.0 原生集成；API 价格约为 GPT-4o-mini 的 1/10 |
| Embedding | 阿里云百炼 (`qwen3.7-text-embedding`) | 1024 维，中文语义理解出色；OpenAI 兼容 API 零额外适配成本；与 DeepSeek API 环境变量隔离 |

> **v2 注**：OpenAI 兼容模式对接百炼是非官方做法；Spring AI 2.0 已提供官方 `spring-ai-starter-model-alibaba`（DashScope 原生）。当前实现沿用 OpenAI 兼容模式（已验证可用），列为技术债务，择机切换。

> **v2 注（Matryoshka 维度能力）**：2026 主流 embedding 模型普遍支持 Matryoshka 表示（向量可截断至低维而保持相对质量，无需重嵌）。`qwen3.7-text-embedding` 的 Matryoshka 维度档位待接入时验证；若支持，可为两处提供基础：① Semantic Cache（Phase 5.6）用低维（如 256 维）做相似度匹配以降成本；② 分级索引预案（低维粗筛 + 全维精排）。kb_embeddings 的 `vector(1024)` 维度在验证结论出来前保持不变。

**环境变量**：

```bash
export DEEPSEEK_API_KEY=sk-xxx       # DeepSeek 控制台获取
export DASHSCOPE_API_KEY=sk-xxx      # 阿里云百炼控制台获取
```

#### 决策 4：Advisor 链设计原则

Advisor 的执行顺序由 `getOrder()` 决定（Order 越小越外层：before 先执行、after 后执行）。v2 重排原则：**先审计、再鉴权、后限流/预算**——未鉴权请求不得消耗租户限流配额，审计需记录被拒绝/被限流的攻击请求。完整表见第十一章 11.2：

```
Order   10 → AuditTraceAdvisor             (审计埋点，最外层，覆盖全链路含被拒请求)
Order   20 → AuthAdvisor                   (鉴权 + 用户/租户提取入上下文)
Order   30 → TokenBudgetAdvisor            (成本追踪 + 预算拦截，依赖已鉴权的 tenant_id)
Order  100 → RateLimitAdvisor              (限流，依赖已鉴权的用户/租户)
Order  110 → OutputGuardrailAdvisor        (输出护栏，after 阶段外层)
Order  300 → InputSanitizeAdvisor          (输入脱敏/Prompt注入检测，先于记忆避免 PII 落库)
Order  400 → MessageChatMemoryAdvisor      (多轮记忆)
Order  450 → RetrievalTraceAdvisor ★       (检索上下文填充 + 溯源透传)
Order  500 → RetrievalAugmentationAdvisor ★(核心：查询改写+双路检索+RRF+重排+证据注入)
Order 1000 → ToolCallingAdvisor            (工具调用，最靠近模型)
```
