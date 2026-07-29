# 企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告

> **项目定位**：面向企业复杂文档场景的高可用、可溯源、可运维的 RAG Agent 知识库工作台
>
> **技术基座**：Java 21 (虚拟线程) + Spring Boot 4.1 + Spring AI 2.0.0 GA + PostgreSQL 18（主数据库 + pgvector 向量扩展）+ Milvus 2.6（可选分布式向量库）+ MinIO（文档 OSS 存储）+ Elasticsearch 8.19 + Redis 8 + DeepSeek V4（LLM）+ 阿里云百炼 DashScope（Embedding）
>
> **报告性质**：从 0 到 1 的全生命周期落地指南，覆盖战略定位、需求分析、架构设计、分阶段实施、代码实现、测试部署与运维
>
> **编写日期**：2026年7月27日

---

## 目录

- [第一卷：项目全景蓝图（战略层）](#第一卷项目全景蓝图战略层)
  - [第一章：项目背景与市场定位](#第一章项目背景与市场定位)
  - [第二章：技术基座与 Spring AI 2.0 能力矩阵](#第二章技术基座与-spring-ai-20-能力矩阵)
- [第二卷：功能需求全景图（需求层）](#第二卷功能需求全景图需求层)
  - [第三章：功能全景与优先级矩阵](#第三章功能全景与优先级矩阵)
  - [第四章：子系统功能详细设计](#第四章子系统功能详细设计)
- [第三卷：技术架构设计（架构层）](#第三卷技术架构设计架构层)
  - [第五章：总体架构设计](#第五章总体架构设计)
  - [第六章：Maven 多模块工程结构](#第六章maven-多模块工程结构)
  - [第七章：数据架构设计](#第七章数据架构设计)
- [第四卷：分阶段落地路线图（执行层）](#第四卷分阶段落地路线图执行层)
  - [第八章：五阶段实施路线图](#第八章五阶段实施路线图)
- [第五卷：核心模块技术实现（实现层）](#第五卷核心模块技术实现实现层)
  - [第九章：知识入库 ETL 管道](#第九章知识入库-etl-管道)
  - [第十章：混合检索引擎](#第十章混合检索引擎)
  - [第十一章：Agent 对话链路](#第十一章agent-对话链路)
  - [第十二章：安全护栏体系](#第十二章安全护栏体系)
  - [第十三章：可观测性体系](#第十三章可观测性体系)
  - [第十四章：知识库运维](#第十四章知识库运维)
- [第六卷：工程质量保障（质量层）](#第六卷工程质量保障质量层)
  - [第十五章：测试策略](#第十五章测试策略)
  - [第十六章：AI 评估体系](#第十六章ai-评估体系)
  - [第十七章：部署与运维](#第十七章部署与运维)
  - [第十八章：交付验收标准](#第十八章交付验收标准)
- [附录](#附录)

---

# 第一卷：项目全景蓝图（战略层）

## 第一章：项目背景与市场定位

### 1.1 2026 年企业知识库 RAG 市场格局

2026 年是企业 AI 从"实验性 Demo"向"生产级 Agent"跨越的分水岭。全球 AI Agent 市场规模已突破 **187 亿美元**（年增长率 122%），中国企业级 AI 智能体市场规模突破 **320 亿元**。

#### 1.1.1 从 Naive RAG 到 Agentic RAG 的范式迁移

传统 Naive RAG（单次检索 → 拼接 → 生成）在企业深水区场景中暴露出三大致命缺陷：

| 缺陷 | 描述 | 企业影响 |
|------|------|----------|
| **全局视野缺失** | 无法跨数十份文档进行全局主题聚合 | 战略分析类问题无法回答 |
| **多跳推理无能** | 无法处理跨实体关系遍历 | 合规审查、供应链分析失效 |
| **一次检索定终身** | 缺乏自我评估与查询重写的反思能力 | 首次检索失败直接导致回答错误 |

**Agentic RAG** 成为 2026 年主流范式：Agent 自主规划、分解复杂查询、评估检索质量、迭代修正，直至产出验证过的答案。

#### 1.1.2 GraphRAG 的迅速崛起

2026 年 WAIC 技术论坛发布的《企业级 AI 知识引擎白皮书》明确指出，**Agentic GraphRAG** 正在成为替代传统 RAG 的下一代标准范式：

- 向量 RAG 处理**模糊语义匹配**
- 图谱 RAG 处理**精确实体关系查询和多跳逻辑推理**
- 最佳实践是构建 **Vector + Graph 的"双引擎"混合检索架构**

#### 1.1.3 平台化趋势：不再"手搓 RAG"

Dify、阿里云百炼 Knowledge Studio、Microsoft Copilot Studio 等平台以"工业化生产"替代"作坊式手搓"。平台化方案在交付效率（10-20x）、检索准确率（+25%）、总体拥有成本（-60~75%）上全面领先。

**但**：平台方案在深度定制化场景（复杂表格保护、领域专用检索策略、企业权限体系集成）中仍有明显天花板——这正是本项目自研核心 RAG 引擎的战略空间。

#### 1.1.4 2026 年企业采购逻辑转变

企业不再满足于"AI 能不能回答我的问题"，而是追问 **"AI 能不能替我干活"**——能否缩短流程、减少人工、降低成本。

### 1.2 六大核心痛点与技术破局方向

| # | 业务痛点 | 市场普遍表现 | Spring AI 2.0 破局方案 |
|---|---------|-------------|----------------------|
| 1 | **文档解析难** | 表格/扫描件解析碎片化，入库可用率 < 70% | `TikaDocumentReader` + 自定义 `SmartOcrRoutingReader`（文本密度动态路由） |
| 2 | **切分碎片化** | 固定大小切分导致表格/图片结构丢失 | 自定义 `HtmlProtectingSplitter`（JSoup AST 解析 + 保护块提取） |
| 3 | **纯向量检索不稳** | 专有名词/字段名/标题词命中率 < 60% | `VectorStore`(Milvus) + Elasticsearch(BM25) + `RrfFusionReranker` |
| 4 | **回答无依据/幻觉** | 缺乏溯源，出了问题无法排查 | 自定义 `PrefetchRagAdvisor` + Grounding Prompt + 结构化溯源输出 |
| 5 | **链路割裂/难调试** | 入库→检索→生成各环节黑盒 | Spring AI Observability + `AuditTraceAdvisor` 全链路埋点 + Grafana 大盘 |
| 6 | **Agent 扩展难** | 工具集成紧耦合，新增能力改造成本高 | `ChatClient` + `ToolCallingAdvisor` + MCP 协议标准化工具编排 |

### 1.3 项目战略定位：三个"不只是"

```
┌──────────────────────────────────────────────────────────────────────┐
│                    项目战略定位三维模型                              │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   不只是"能回答"          不只是"算法模型"      不只是"一个项目"     │
│   ─────────────            ──────────────        ─────────────       │
│   ✓ 答得准（混合检索）    ✓ 工程闭环           ✓ 可复用组件库     │
│   ✓ 答得有依据（溯源）    ✓ 可视化调试         ✓ 标准化架构       │
│   ✓ 答得稳定（证据注入）  ✓ 持续迭代           ✓ 团队能力沉淀     │
│                                                                      │
│   战略目标：打造企业级"可解释、可运维、可进化"的 AI 知识中枢         │
└──────────────────────────────────────────────────────────────────────┘
```

**核心价值主张**：

- **对业务**：带来源溯源的精准问答（Top-5 命中率 > 85%），降低知识获取成本 60%+
- **对运维**：从文档解析、Chunk 观测到检索调试的全链路可视化工作台
- **对技术**：沉淀 Spring AI 2.0 企业级 RAG 标准组件库，可复用于其他 AI 项目

---

## 第二章：技术基座与 Spring AI 2.0 能力矩阵

### 2.1 技术选型全景

| 层级 | 技术选型 | 版本 | 选型理由 |
|------|---------|------|---------|
| **语言** | Java | 21 LTS | 虚拟线程、结构化并发、Record、Pattern Matching |
| **框架** | Spring Boot | 4.1.x | Spring AI 2.0 基线要求 |
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
| **ChatMemory** | 多轮对话 | RedisChatMemory 实现 |

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
- 通过 `spring.ai.vectorstore.type=custom` 禁用 Spring AI 原生 auto-config，改由 `@ConditionalOnProperty` 自定义条件装配
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
│  ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─   │
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

**环境变量**：

```bash
export DEEPSEEK_API_KEY=sk-xxx       # DeepSeek 控制台获取
export DASHSCOPE_API_KEY=sk-xxx      # 阿里云百炼控制台获取
```

#### 决策 4：Advisor 链设计原则

Advisor 的执行顺序由 `getOrder()` 决定，本项目设计如下：

```
Order 100  → RateLimitAdvisor       (限流，最外层)
Order 200  → AuthAdvisor            (鉴权)
Order 300  → InputSanitizeAdvisor   (输入脱敏/Prompt注入检测)
Order 400  → MessageChatMemoryAdvisor (多轮记忆)
Order 500  → PrefetchRagAdvisor     (RAG 检索+证据注入，核心)
Order 1000 → ToolCallingAdvisor     (工具调用，最靠近模型)
Order 110  → OutputGuardrailAdvisor (输出护栏)
Order  50  → AuditTraceAdvisor      (审计埋点，覆盖全链路)
Order  10  → TokenBudgetAdvisor     (成本追踪)
```

---

# 第二卷：功能需求全景图（需求层）

## 第三章：功能全景与优先级矩阵

### 3.1 优先级定义

| 优先级 | 定义 | 交付阶段 | 典型特征 |
|--------|------|---------|---------|
| **P0** | MVP 必备，没有它系统不可用 | Phase 1 (W1-W3) | 核心路径，最小闭环 |
| **P1** | 核心差异化竞争力 | Phase 2 (W4-W7) | 与竞品拉开差距的关键能力 |
| **P2** | 企业级必备 | Phase 3-4 (W8-W16) | 安全、合规、运维 |
| **P3** | 智能化与持续进化 | Phase 5 (W17-W24) | 高级特性，锦上添花 |

### 3.2 功能全景矩阵

#### P0 - MVP 必备（Phase 1，第 1-3 周）

| 编号  | 功能点 | 所属子系统 | 核心价值 |
|-------|-------|----------|---------|
| P0-01 | 基础对话 API（同步 + 流式） | Agent 对话 | 跑通 LLM 调用链路 |
| P0-02 | 文档上传（PDF/Docx/MD/TXT） | 知识采编 | 知识入库入口 |
| P0-03 | Tika 原生文档解析 | 知识采编 | 基础文档解析能力 |
| P0-04 | TokenTextSplitter 基础切分 | 知识采编 | 文本分块 |
| P0-05 | Milvus 向量化存储 | 知识采编 | 向量入库 |
| P0-06 | 单路向量检索（QuestionAnswerAdvisor） | 智能检索 | 基础 RAG 问答 |
| P0-07 | 前端基础对话界面 | Agent 对话 | 用户交互入口 |
| P0-08 | 后端基础工程骨架（多模块 Maven） | 工程基础 | 可持续开发的架构 |
| P0-09 | PostgreSQL 核心表结构 | 数据架构 | 数据持久化 |
| P0-10 | 基础日志与异常处理 | 可观测性 | 问题排查能力 |
| P0-11 | 统一 API 响应格式 | 工程基础 | API 规范 |

#### P1 - 核心差异化（Phase 2，第 4-7 周）

| 编号 | 功能点 | 所属子系统 | 核心价值 |
|------|-------|----------|---------|
| P1-01 | SmartOcrRoutingReader 双链路解析 | 知识采编 | 扫描件/复杂表格可入库 |
| P1-02 | HtmlProtectingSplitter 保护式切分 | 知识采编 | 表格/图片结构不丢失 |
| P1-03 | Elasticsearch BM25 全文检索 | 智能检索 | 关键词精准匹配 |
| P1-04 | 混合检索（向量 + BM25 并行） | 智能检索 | 专有名词命中率提升 |
| P1-05 | RRF 融合排序算法 | 智能检索 | 双路结果科学融合 |
| P1-06 | 检索结果重排序（Reranker） | 智能检索 | 进一步提升精度 |
| P1-07 | PG-Milvus 解耦存储 | 知识采编 | Chunk 可编辑的基础 |
| P1-08 | 分层知识管理（Document→Section→Chunk→Vector） | 知识采编 | 数据结构清晰 |
| P1-09 | 异步 ETL 管道（@Async + 进度回调） | 知识采编 | 大文件不阻塞 |
| P1-10 | PrefetchRagAdvisor 证据注入 | Agent 对话 | 回答稳定性增强 |
| P1-11 | 来源溯源输出（Chunk ID/文件名/页码/得分） | 溯源可解释 | 答案有据可查 |
| P1-12 | SSE 流式 Token + Trace 事件推送 | Agent 对话 | 流式体验 + 溯源卡片 |
| P1-13 | 前端检索调试台（展示各路得分） | 溯源可解释 | 可调试可优化 |
| P1-14 | 前端 Chunk 观测台 | 知识库运维 | Chunk 可视化 |
| P1-15 | 前端文档管理界面 | 知识库运维 | 文档生命周期管理 |

#### P2 - 企业级必备（Phase 3-4，第 8-16 周）

| 编号 | 功能点 | 所属子系统 | 核心价值 |
|------|-------|----------|---------|
| P2-01 | 多轮对话记忆（MessageChatMemoryAdvisor） | Agent 对话 | 上下文连贯 |
| P2-02 | 多租户数据隔离（FilterExpression 过滤 tenant_id） | 安全治理 | SaaS 化基础 |
| P2-03 | RBAC 知识库权限管控（部门/角色级） | 安全治理 | 数据安全 |
| P2-04 | InputSanitizeAdvisor（PII 脱敏 + Prompt 注入检测） | 安全治理 | 输入安全 |
| P2-05 | OutputGuardrailAdvisor（敏感词/竞品/幻觉拦截） | 安全治理 | 输出安全 |
| P2-06 | RateLimitAdvisor（Redisson 令牌桶限流） | 安全治理 | 资源保护 |
| P2-07 | TokenBudgetAdvisor（成本追踪+预算控制） | 安全治理 | 成本可控 |
| P2-08 | AuditTraceAdvisor 全链路审计日志 | 可观测性 | 全程可追溯 |
| P2-09 | AiBusinessMetrics 业务指标采集 | 可观测性 | 业务可度量 |
| P2-10 | Prometheus + Grafana 监控大盘 | 可观测性 | 可视化监控 |
| P2-11 | Chunk CRUD 运维 API | 知识库运维 | 知识可编辑 |
| P2-12 | 文档级物理删除 + 索引重建 | 知识库运维 | 知识可管理 |
| P2-13 | Prompt 版本化管理（Redis + DB 双层缓存） | 知识库运维 | Prompt 可迭代 |
| P2-14 | 问答日志落库与历史查询 | 知识库运维 | 数据可回溯 |
| P2-15 | 用户反馈收集（点赞/点踩 + 期望回答） | 知识库运维 | 反馈闭环基础 |
| P2-16 | 多模型路由 + Fallback（SmartRoutingChatModel） | Agent 对话 | 高可用 |
| P2-17 | @Tool 注解工具调用（对接内部 OA/ERP） | Agent 对话 | Agent 行动能力 |
| P2-18 | OpenTelemetry 全链路追踪集成 | 可观测性 | 分布式追踪 |

#### P3 - 智能化与持续进化（Phase 5，第 17-24 周）

| 编号 | 功能点 | 所属子系统 | 核心价值 |
|------|-------|----------|---------|
| P3-01 | GraphRAG 知识图谱集成（Neo4j） | 智能检索 | 多跳推理 + 全局摘要 |
| P3-02 | Multi-Agent 多智能体协作 | Agent 对话 | 复杂任务分解 |
| P3-03 | 查询意图识别与自适应路由 | 智能检索 | 智能分流 |
| P3-04 | 多知识库动态路由 | 智能检索 | 不同领域独立检索 |
| P3-05 | Semantic Cache 语义缓存（Redis RediSearch） | 智能检索 | 降低成本 |
| P3-06 | 对话反馈自动微调闭环（RLHF） | 知识库运维 | 持续优化 |
| P3-07 | A/B 测试框架（Prompt 效果对比） | AI 评估 | 数据驱动迭代 |
| P3-08 | LLM-as-Judge 自动评估 | AI 评估 | 自动化质量检查 |
| P3-09 | Golden Dataset 回归测试 CI 集成 | AI 评估 | 防止 Prompt 变更回归 |
| P3-10 | 智能检索参数自适应（RL 优化 topK/阈值） | 智能检索 | 自动调优 |
| P3-11 | MCP Server 宿主（对外暴露知识库为 MCP 服务） | Agent 对话 | 开放生态 |
| P3-12 | 移动端适配 / 企业微信/钉钉集成 | Agent 对话 | 多端触达 |

---

## 第四章：子系统功能详细设计

### 4.1 知识采编子系统

**核心流程**：文档上传 → 格式检测 → 解析路由 → 保护式切分 → 元数据增强 → PG 持久化 → 向量化 → Milvus 写入

```
┌────────────┐    ┌──────────────┐    ┌────────────────┐    ┌──────────────┐
│ 文档上传   │───→│ 格式检测与   │───→│ 解析路由决策   │───→│ 内容提取     │
│ (Multipart)│    │ 文本密度探测 │    │ (NATIVE / OCR) │    │ (Tika/OCR)   │
└────────────┘    └──────────────┘    └────────────────┘    └──────┬───────┘
                                                                   │
┌──────────┐    ┌──────────────┐    ┌─────────────┐         ┌──────▼───────┐
│ Milvus   │←───│ Embedding    │←───│ PG 持久化   │←────────│ 保护式切分   │
│ 向量写入 │    │ Model 向量化 │    │ (kb_chunk)  │         │ (HTML保护)   │
└──────────┘    └──────────────┘    └─────────────┘         └──────────────┘
```

**详细功能点**：

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 多格式上传 | PDF、Docx、Markdown、TXT、HTML | Spring MVC MultipartFile | P0 |
| 文本密度探测 | 读取前几页计算文本密度，判断扫描件 | PDFBox 文本提取 + 启发式规则 | P1 |
| Tika 原生解析 | 电子文档直接提取文本 | `TikaDocumentReader` | P0 |
| OCR 智能路由 | 扫描件/复杂表格走 OCR 链路 | 自定义 `SmartOcrRoutingReader`，对接阿里云/百度 OCR API | P1 |
| HTML 结构保护 | OCR 返回的 `<table>`、`<img>` 标签保护 | JSoup 解析 + 正则匹配 | P1 |
| Token 级别切分 | 按 Token 数切分，保持语义完整 | 自定义 `HtmlProtectingSplitter`（继承 `DocumentTransformer`） | P0 |
| 元数据增强 | 注入 doc_id、page_num、section_id、chunk_type | Metadata Map 操作 | P0 |
| 异步 ETL | 大文件解析不阻塞主线程 | `@Async` + 虚拟线程 + WebSocket 进度推送 | P1 |
| 表格/图片 Chunk | 表格和图片作为独立特殊 Chunk | `chunk_type = TABLE / IMAGE` | P1 |
| 增量更新 | 文档重新上传后智能更新 Chunk | 版本号 + Diff 算法 | P2 |
| 批量导入 | 支持 ZIP 包上传 + 目录结构保持 | ZIP 解压 + 递归处理 | P2 |

### 4.2 智能检索子系统

**核心流程**：用户 Query → 查询改写/扩展 → 并行召回（向量 + BM25）→ RRF 融合 → 重排序 → Top-K 结果

```
                          ┌──────────────────┐
                          │   用户 Query     │
                          └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐
                          │   查询改写/扩展  │ (可选：同义词扩展、HyDE)
                          └────────┬─────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
    ┌─────────▼─────────┐ ┌────────▼─────────┐ ┌────────▼────────┐
    │ Milvus 向量检索   │ │ ES BM25 检索     │ │ 元数据过滤      │
    │ (语义 Top-K*2)    │ │ (关键词 Top-K*2) │ │ (tenant/权限)   │
    └─────────┬─────────┘ └────────┬─────────┘ └────────┬────────┘
              │                    │                    │
              └────────────────────┼────────────────────┘
                                   │
                          ┌────────▼─────────┐
                          │   RRF 融合排序   │  score = 1/(K+rank)
                          └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐
                          │   重排序 (Rerank)│  BGE-Reranker / Cohere
                          └────────┬─────────┘
                                   │
                          ┌────────▼─────────┐
                          │   Top-K 结果     │  含各项得分和溯源信息
                          └──────────────────┘
```

**详细功能点**：

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 单路向量检索 | 基于 Milvus 的语义相似度检索 | `VectorStore.similaritySearch(SearchRequest)` | P0 |
| BM25 关键词检索 | Elasticsearch 全文检索 | ElasticsearchClient + BM25 算法 | P1 |
| 并行双路召回 | 虚拟线程并发执行 | `StructuredTaskScope.ShutdownOnFailure` | P1 |
| RRF 融合排序 | Reciprocal Rank Fusion 算法 | 自定义 `RrfFusionReranker`（K=60） | P1 |
| 重排序 | Cross-Encoder 精细化排序 | BGE-Reranker-v2 / Cohere Rerank API | P1 |
| 元数据过滤 | 按 tenant_id、doc_id、chunk_type 过滤 | Milvus `filterExpression` + ES `filter` | P2 |
| 查询改写 | 结合历史对话改写 Query | `MessageChatMemoryAdvisor` 协作 | P2 |
| 多知识库路由 | 不同主题路由到不同 VectorStore | 自定义 `MultiKnowledgeBaseRouter` | P3 |
| Semantic Cache | 相似问题命中缓存直接返回 | Redis RediSearch + 语义相似度 | P3 |
| 检索参数自适应 | RL 优化 topK、相似度阈值 | 强化学习 / 贝叶斯优化 | P3 |

### 4.3 Agent 对话子系统

**核心流程**：用户提问 → Advisor 链处理 → Prefetch 预检索 → 证据注入 → LLM 生成 → 溯源标注 → SSE 流式推送

```
用户 Query
    │
    ▼
┌──────────────────────────────────────────────────────┐
│                  ChatClient.prompt()                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────┐  │
│  │RateLimit │→ │Auth      │→ │Sanitize  │→ │Memory│  │
│  │(100)     │  │(200)     │  │(300)     │  │(400) │  │
│  └──────────┘  └──────────┘  └──────────┘  └──┬───┘  │
│                                               │      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──▼───┐  │
│  │Audit     │← │Guardrail │← │LLM Call  │← │RAG   │  │
│  │(50)      │  │(100)     │  │          │  │(500) │  │
│  └──────────┘  └──────────┘  └──────────┘  └──────┘  │
└──────────────────────────────────────────────────────┘
    │
    ▼
SSE Stream → 前端
  ├── event: TOKEN (流式文本块)
  ├── event: TRACE (溯源卡片数据)
  ├── event: TOOL_CALL (工具调用状态)
  └── event: ERROR (异常信息)
```

**详细功能点**：

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 基础对话 | 同步 + 流式对话 | `ChatClient.call()` / `.stream()` | P0 |
| Prefetch 预检索 | 模型生成前强制检索知识库 | 自定义 `PrefetchRagAdvisor` | P1 |
| 证据注入 | 检索结果注入 System Prompt | Grounding Prompt 模板 | P1 |
| SSE 流式推送 | Token 级别实时推送 | `Flux<ServerSentEvent>` | P1 |
| 多轮对话记忆 | 上下文会话管理 | `MessageChatMemoryAdvisor` + Redis | P2 |
| 工具调用 | @Tool 注解对接 OA/ERP/数据库 | `ToolCallingAdvisor` + `@Tool` | P2 |
| 多模型路由 | 按复杂度选择模型 + Fallback | 自定义 `SmartRoutingChatModel` | P2 |
| 引用标注 | 回答中自动插入 [ref-N] | `GroundingAdvisor` 后处理 | P1 |
| 溯源事件推送 | 流结束时推送完整溯源数据 | SSE TRACE 事件 | P1 |
| MCP Server 宿主 | 将知识库对外暴露为 MCP 服务 | Spring AI MCP Server 2.0 | P3 |
| Multi-Agent 协作 | 多 Agent 并行处理复杂任务 | Orchestrator-Subagent 模式 | P3 |

### 4.4 溯源与可解释性子系统

**溯源数据模型**：

```java
public record AgentResponse(
    String answer,                    // 最终回答文本
    List<SourceCitation> sources,     // 引用来源列表
    RetrievalDetail retrievalDetail,  // 检索详情
    TokenUsage tokenUsage             // Token 消耗
) {}

public record SourceCitation(
    String chunkId,       // Chunk 唯一标识
    String fileName,      // 来源文件名
    Integer pageNum,      // 页码
    String content,       // Chunk 原文片段
    Double vectorScore,   // 向量相似度得分
    Double bm25Score,     // BM25 得分
    Double fusionScore,   // RRF 融合得分
    String chunkType      // TEXT / TABLE / IMAGE
) {}
```

**详细功能点**：

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 来源标注 | 回答中自动标注 [ref-N] | `GroundingAdvisor` 后处理 | P1 |
| 溯源卡片 | 前端展示来源文件/页码/得分 | SSE TRACE 事件 + Vue3 卡片组件 | P1 |
| 检索得分透明化 | 前端展示各路检索得分 | 检索调试台 API | P1 |
| Chunk 原文回看 | 点击引用查看完整 Chunk 内容 | kb_chunk 查询 API | P1 |
| 检索详情日志 | 每次检索完整记录 | `kb_audit_log.retrieved_chunks(JSONB)` | P2 |
| 答案溯源链 | 答案→Chunk→Document 完整链路 | 数据关联查询 | P2 |

### 4.5 知识库运维子系统

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 文档管理 | 上传/删除/状态查看 | REST API + Spring Data JPA | P0 |
| Chunk 观测 | 可视化查看文档的所有 Chunk | 前端 Chunk 列表 + 搜索 | P1 |
| Chunk 编辑 | 修改 Chunk 文本并重新向量化 | PUT API → `@Async` 更新 Milvus | P2 |
| Chunk 删除 | 软删除（is_deleted=true） | PATCH API → 同步更新 Milvus | P2 |
| 文档物理删除 | 级联删除文档及关联数据 | DELETE API → PG CASCADE + Milvus delete | P2 |
| 索引重建 | 全量/增量重建向量索引 | Admin API → 异步批处理任务 | P2 |
| 解析状态追踪 | 文档解析进度实时更新 | WebSocket / 轮询 | P1 |
| 问答日志查询 | 按时间/用户/会话检索历史 | `kb_audit_log` + `kb_message` 查询 | P2 |
| Prompt 版本管理 | 模板的新增/编辑/回滚/AB测试 | Redis 缓存 + DB 持久化 | P2 |

### 4.6 安全与治理子系统

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 多租户隔离 | tenant_id 级别的数据隔离 | FilterExpression + Milvus 标量过滤 | P2 |
| RBAC 权限 | 部门/角色级知识库可见性 | Spring Security 7 + 动态过滤表达式 | P2 |
| PII 脱敏 | 手机号/身份证/银行卡号掩码 | `InputSanitizeAdvisor` 正则替换 | P2 |
| Prompt 注入检测 | 恶意指令识别与拦截 | 关键词规则 + LLM 辅助检测 | P2 |
| 输出合规审查 | 敏感词/竞品/违规内容过滤 | `OutputGuardrailAdvisor` 规则链 | P2 |
| 限流控制 | 用户/IP/租户级 QPS 限制 | `RateLimitAdvisor` + Redisson 令牌桶 | P2 |
| Token 预算 | 单次/日/月 Token 消耗上限 | `TokenBudgetAdvisor` + Redis 计数 | P2 |
| 审计日志 | 全链路操作记录（不可篡改） | `AuditTraceAdvisor` → PG 持久化 | P2 |
| API Key 管理 | 密钥轮换 + Vault 集成 | Spring Vault / K8s Secrets | P2 |

### 4.7 可观测性子系统

| 功能 | 描述 | 技术实现 | 优先级 |
|------|------|---------|--------|
| 全链路 Trace | HTTP → Advisor → 检索 → LLM 全链路追踪 | OpenTelemetry + Spring AI 原生集成 | P2 |
| LLM 调用指标 | Token 消耗、延迟、成功率 | `spring.ai.chat.model.token.usage` | P1 |
| 检索性能指标 | 检索延迟、命中率、召回数 | `spring.ai.vector.store.search.duration` | P1 |
| 业务指标 | 点赞率、命中率、工具成功率 | 自定义 `AiBusinessMetrics` + Micrometer | P2 |
| Grafana 大盘 | 4 个 Dashboard（概览/检索/LLM/业务） | Prometheus + Grafana 配置 | P2 |
| 告警规则 | 延迟超阈值/错误率飙升/预算耗尽 | Prometheus AlertManager | P2 |
| Bad Case 分析 | 问答日志查询 + 检索得分回看 | 前端审计看板 | P2 |

---

# 第三卷：技术架构设计（架构层）

## 第五章：总体架构设计

### 5.1 四层架构

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                      接入层 (Access Layer)                                   │
│  ┌───────────────────┐  ┌───────────────────┐  ┌──────────────────────────┐  │
│  │ Vue3 知识库工作台 │  │ Spring Cloud      │  │ 外部系统 (MCP Client)    │  │
│  │ · 文档管理        │  │ Gateway           │  │ · OA/ERP 系统            │  │
│  │ · Chunk 观测      │  │ · 限流/鉴权/路由  │  │ · 第三方 AI 平台         │  │
│  │ · 检索调试台      │  │ · SSE 代理        │  │ · 企业微信/钉钉          │  │
│  │ · Agent 对话窗    │  │ · 日志聚合        │  │                          │  │
│  │ · 运维审计看板    │  │                   │  │                          │  │
│  └───────────────────┘  └───────────────────┘  └──────────────────────────┘  │
└─────────────────────────────────┬────────────────────────────────────────────┘
                                  │ REST / SSE / WebSocket
┌─────────────────────────────────▼────────────────────────────────────────────┐
│                      AI 编排层 (Orchestration Layer)                         │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │                        ChatClient (Fluent API)                         │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │  │
│  │  │RateLimit │→│  Auth    │→│ Sanitize │→│  Memory  │→│   RAG    │→...  │  │
│  │  │  100     │ │  200     │ │  300     │ │  400     │ │   500    │      │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘      │  │
│  │  ...→ ┌──────────┐ ┌──────────┐ ┌──────────┐                           │  │
│  │       │ToolCall  │→│Guardrail │→│  Audit   │                           │  │
│  │       │  1000    │ │  110     │ │   50     │                           │  │
│  │       └──────────┘ └──────────┘ └──────────┘                           │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐    │
│  │ SmartRouting     │  │ PromptTemplate   │  │ ToolCallingManager       │    │
│  │ ChatModel        │  │ Manager          │  │ (@Tool + MCP Registry)   │    │
│  │ (路由+Fallback)  │  │ (版本化+AB测试)  │  │                          │    │
│  └──────────────────┘  └──────────────────┘  └──────────────────────────┘    │
└─────────────────────────────────┬────────────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼────────────────────────────────────────────┐
│                      能力层 (Capability Layer)                               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐    │
│  │ DocumentETL      │  │ HybridRetriever  │  │ Rerank Model             │    │
│  │ Pipeline         │  │                  │  │                          │    │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────────────┐ │    │
│  │ │SmartOcrRouter│ │  │ │Milvus Vector │ │  │ │BGE-Reranker-v2       │ │    │
│  │ │Tika + OCR API│ │  │ │ES BM25       │ │  │ │Cohere Rerank API     │ │    │
│  │ └──────────────┘ │  │ │RRF Fusion    │ │  │ │Cross-Encoder (本地)  │ │    │
│  │ ┌──────────────┐ │  │ └──────────────┘ │  │ └──────────────────────┘ │    │
│  │ │HtmlProtecting│ │  └──────────────────┘  └──────────────────────────┘    │
│  │ │Splitter      │ │                                                        │
│  │ └──────────────┘ │  ┌──────────────────┐  ┌──────────────────────────┐    │
│  └──────────────────┘  │ Query Rewriter   │  │ OCR Router               │    │
│                        │ (同义词/HyDE)    │  │ (Tesseract/Cloud API)    │    │
│                        └──────────────────┘  └──────────────────────────┘    │
└─────────────────────────────────┬────────────────────────────────────────────┘
                                  │
┌─────────────────────────────────▼────────────────────────────────────────────┐
│                    基础设施层 (Infrastructure Layer)                         │
│  ┌──────────┐ ┌───────────┐ ┌───────────┐ ┌─────────────┐ ┌─────────────┐    │
│  │ Milvus   │ │PostgreSQL │ │  Redis    │ │Elasticsearch│ │ MinIO (OSS) │    │
│  │ (向量库) │ │ (主数据库)│ │(缓存/记忆)│ │ (BM25)      │ │ (文档存储)  │    │
│  └──────────┘ └───────────┘ └───────────┘ └─────────────┘ └─────────────┘    │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │ OpenTelemetry → Micrometer → Prometheus → Grafana → AlertManager     │    │
│  └──────────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Advisor 拦截器链完整设计

本项目自定义 **8 个 Advisor**，构成完整的请求处理管道：

| Order | Advisor | 接口 | 职责 | 阶段 |
|-------|---------|------|------|------|
| **100** | `RateLimitAdvisor` | `BaseAdvisor` (before) | Redisson 令牌桶限流，用户/IP/租户级 | P2 |
| **200** | `AuthAdvisor` | `BaseAdvisor` (before) | JWT 鉴权，提取用户/租户信息 | P2 |
| **300** | `InputSanitizeAdvisor` | `BaseAdvisor` (before) | PII 脱敏、Prompt 注入检测、敏感词过滤 | P2 |
| **400** | `MessageChatMemoryAdvisor` | `BaseAdvisor` | 多轮对话记忆（Redis），历史上下文注入 | P2 |
| **500** | `PrefetchRagAdvisor` | `BaseAdvisor` (before) | **核心**：混合检索 + 证据注入 + 元数据透传 | P1 |
| **1000** | `ToolCallingAdvisor` | `CallAdvisor` | 工具调用循环，@Tool + MCP 工具编排 | P2 |
| **110** | `OutputGuardrailAdvisor` | `BaseAdvisor` (after) | 输出合规审查、幻觉拦截、竞品过滤 | P2 |
| **50** | `AuditTraceAdvisor` | `BaseAdvisor` (before+after) | 全链路审计日志落库 | P2 |
| **10** | `TokenBudgetAdvisor` | `BaseAdvisor` (before+after) | Token 消耗统计、成本追踪、预算告警 | P2 |

### 5.3 Java 21 虚拟线程应用策略

| 应用场景 | 并发模型 | 收益 |
|---------|---------|------|
| 混合检索（向量+BM25） | `StructuredTaskScope.ShutdownOnFailure` | 双路并行，总延迟 = max(向量, BM25) |
| 文档 ETL 管道 | `@Async` + 虚拟线程 | 异步非阻塞，Web 线程立即返回 |
| SSE 流式推送 | `Flux` + 虚拟线程 | 非阻塞 I/O，支持高并发连接 |
| OCR API 调用 | 虚拟线程 HTTP 客户端 | 避免线程池耗尽 |
| 批量向量化 | `parallelStream()` + 虚拟线程 | CPU 密集型任务并行加速 |

**关键注意事项**：确保底层 HTTP 客户端（RestClient/WebClient）支持虚拟线程，避免 synchronized 块导致 Pinning。

---

## 第六章：Maven 多模块工程结构

### 6.1 模块划分

```
kb-rag-agent/                           # 父工程
├── pom.xml                             # 父 POM（依赖管理 + BOM）
├── kb-commons/                         # 通用模块
│   └── src/main/java/com/enterprise/kb/commons/
│       ├── dto/                        # 通用 DTO（PageResult, ApiResponse）
│       ├── exception/                  # 业务异常体系
│       ├── constant/                   # 常量定义
│       └── util/                       # 工具类
├── kb-domain/                          # 领域模块
│   └── src/main/java/com/enterprise/kb/domain/
│       ├── model/                      # JPA Entity（KbDocument, KbChunk, ...）
│       ├── repository/                 # Spring Data JPA Repository
│       ├── vo/                         # VO 对象
│       └── enums/                      # 枚举（DocumentStatus, ChunkType, ParseRoute）
├── kb-infrastructure/                  # 基础设施模块
│   └── src/main/java/com/enterprise/kb/infrastructure/
│       ├── vectorstore/                # 向量库双后端配置（pgvector + Milvus 可切换）
│       ├── milvus/                     # MilvusServiceClient 配置
│       ├── elasticsearch/              # ElasticsearchClient 配置
│       ├── redis/                      # Redis 配置 + ChatMemory 实现
│       ├── minio/                      # MinIO OSS 适配
│       └── ocr/                        # OCR API 客户端
├── kb-etl/                             # ETL 管道模块（独立可部署）
│   └── src/main/java/com/enterprise/kb/etl/
│       ├── reader/                     # SmartOcrRoutingReader 等
│       ├── transformer/                # HtmlProtectingSplitter 等
│       ├── writer/                     # PgWriter, MilvusWriter
│       ├── pipeline/                   # DocumentEtlPipeline Builder
│       └── service/                    # EtlService, OcrRouterService
├── kb-ai-core/                         # AI 核心模块
│   └── src/main/java/com/enterprise/kb/ai/
│       ├── config/                     # ChatClient 配置、模型 Bean 定义
│       │   └── dashscopeEmbeddingConfig# 百炼 Embedding（OpenAI 兼容）
│       ├── advisor/                    # 9 个自定义 Advisor
│       ├── chat/                       # DeepSeekChatModel 集成
│       ├── tool/                       # @Tool 工具注册
│       ├── prompt/                     # PromptTemplateManager
│       ├── retriever/                  # HybridRetrievalService, RrfFusionReranker
│       └── metrics/                    # AiBusinessMetrics
├── kb-api/                             # 对外 API 模块
│   └── src/main/java/com/enterprise/kb/api/
│       ├── controller/                 # REST Controller
│       │   ├── AgentController         # SSE 流式对话
│       │   ├── DocumentController      # 文档管理
│       │   ├── KnowledgeController     # 知识检索
│       │   └── SessionController       # 会话管理
│       ├── dto/                        # API 专用 DTO
│       └── config/                     # Web 配置（CORS, SSE 超时）
├── kb-admin/                           # 运维后台模块
│   └── src/main/java/com/enterprise/kb/admin/
│       ├── controller/                 # Admin Controller
│       │   ├── ChunkAdminController    # Chunk CRUD + 索引重建
│       │   ├── AuditAdminController    # 审计日志查询
│       │   └── PromptAdminController   # Prompt 版本管理
│       └── dto/
└── kb-eval/                            # AI 评估模块
    └── src/main/java/com/enterprise/kb/eval/
        ├── dataset/                    # Golden Dataset 加载
        ├── metric/                     # ContextRelevance, Faithfulness 等指标
        └── runner/                     # 评估执行器
```

### 6.2 模块依赖关系

```
kb-commons            ← 无依赖（基础层）
    ↑
kb-domain             ← 依赖 kb-commons
    ↑
kb-infrastructure     ← 依赖 kb-domain（kb-commons 传递可得）
    ↑          ↑
kb-etl     kb-ai-core ← 依赖 kb-infrastructure（kb-domain + kb-commons 传递可得）
    ↑          ↑  ↑
    └─────┬────┘  ├── kb-admin
          ↑       ├── kb-eval
          │       └── ← 依赖 kb-ai-core（kb-domain + kb-infrastructure 传递可得）
       kb-api         ← 依赖 kb-etl + kb-ai-core
```

---

## 第七章：数据架构设计

### 7.1 PostgreSQL 核心表设计

```sql
-- ============================================
-- PostgreSQL 18 DDL（所有建表语句后执行独立 CREATE INDEX）
-- ============================================

-- 1. 文档主表
CREATE TABLE kb_document (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(36) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    original_name   VARCHAR(500),
    type            VARCHAR(20) NOT NULL,           -- PDF, DOCX, MD, TXT
    size            BIGINT,
    oss_path        VARCHAR(500),
    status          VARCHAR(20) DEFAULT 'UPLOADING',
    parse_route     VARCHAR(20),                   -- NATIVE, OCR
    page_count      INT,
    table_count     INT,
    image_count     INT,
    chunk_count     INT,
    error_message   TEXT,
    created_by      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_tenant_status ON kb_document (tenant_id, status);
CREATE INDEX idx_created_at ON kb_document (created_at);

-- 2. 章节表
CREATE TABLE kb_section (
    id              VARCHAR(36) PRIMARY KEY,
    doc_id          VARCHAR(36) NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    parent_id       VARCHAR(36),
    title           VARCHAR(500),
    level           INT DEFAULT 1,
    order_index     INT,
    page_start      INT,
    page_end        INT
);
CREATE INDEX idx_doc_section ON kb_section (doc_id, order_index);

-- 3. 切分块表（核心业务表）
CREATE TABLE kb_chunk (
    id              VARCHAR(36) PRIMARY KEY,
    doc_id          VARCHAR(36) NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    section_id      VARCHAR(36),
    chunk_index     INT NOT NULL,
    content         TEXT NOT NULL,
    original_content TEXT,
    page_num        INT,
    token_count     INT,
    metadata        JSONB DEFAULT '{}',
    chunk_type      VARCHAR(20) DEFAULT 'TEXT',    -- TEXT, TABLE, IMAGE
    vector_id       VARCHAR(100),
    is_deleted      BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_doc_chunk ON kb_chunk (doc_id, chunk_index);
CREATE INDEX idx_vector_id ON kb_chunk (vector_id);
CREATE INDEX idx_chunk_type ON kb_chunk (chunk_type);
-- PG 全文检索 GIN 索引（admin 管理后台查询用，非检索主路径）
CREATE INDEX idx_ft_content ON kb_chunk USING GIN (to_tsvector('simple', content));

-- 4. 会话表
CREATE TABLE kb_session (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(36) NOT NULL,
    user_id         VARCHAR(50) NOT NULL,
    title           VARCHAR(255),
    knowledge_base  VARCHAR(100),
    status          VARCHAR(20) DEFAULT 'ACTIVE',
    message_count   INT DEFAULT 0,
    total_tokens    BIGINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_user_session ON kb_session (user_id, updated_at DESC);

-- 5. 消息表
CREATE TABLE kb_message (
    id              VARCHAR(36) PRIMARY KEY,
    session_id      VARCHAR(36) NOT NULL REFERENCES kb_session(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT,
    citations       JSONB,
    token_usage     JSONB,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_session_msg ON kb_message (session_id, created_at);

-- 6. 审计日志表
CREATE TABLE kb_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    trace_id        VARCHAR(100),
    session_id      VARCHAR(36),
    user_id         VARCHAR(50),
    tenant_id       VARCHAR(36),
    query_text      TEXT NOT NULL,
    rewritten_query TEXT,
    retrieval_type  VARCHAR(30),
    retrieved_chunks JSONB,
    reranked_chunks  JSONB,
    final_answer    TEXT,
    model_name      VARCHAR(100),
    latency_ms      INT,
    token_usage     JSONB,
    feedback        VARCHAR(10),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_trace ON kb_audit_log (trace_id);
CREATE INDEX idx_audit_session ON kb_audit_log (session_id);
CREATE INDEX idx_audit_user ON kb_audit_log (user_id);
CREATE INDEX idx_audit_created ON kb_audit_log (created_at DESC);

-- 7. 用户反馈表
CREATE TABLE kb_feedback (
    id              VARCHAR(36) PRIMARY KEY,
    message_id      VARCHAR(36) NOT NULL REFERENCES kb_message(id),
    audit_log_id    BIGINT REFERENCES kb_audit_log(id),
    user_id         VARCHAR(50),
    rating          VARCHAR(10) NOT NULL,
    expected_answer TEXT,
    feedback_tags   JSONB,
    resolved        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 8. Prompt 模板表
CREATE TABLE kb_prompt_template (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    version         VARCHAR(20) NOT NULL,
    template_text   TEXT NOT NULL,
    variables       JSONB,
    status          VARCHAR(20) DEFAULT 'DRAFT',
    ab_test_group   VARCHAR(20),
    created_by      VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_name_version UNIQUE (name, version)
);
```

### 7.2 pgvector 向量表设计（pgvector 模式）

```sql
-- pgvector 模式下，向量存储在 PostgreSQL 同一实例中
-- 表由 Spring AI PgVectorStore 自动创建（initialize-schema=true），也可手动执行

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kb_embeddings (
    id          VARCHAR(36) PRIMARY KEY,           -- 对应 kb_chunk.id
    embedding   vector(1024),                      -- 向量（维度与 EmbeddingModel 一致）
    content     TEXT,                               -- Chunk 原文冗余（可选，便于调试）
    metadata    JSONB DEFAULT '{}',                -- chunk_id / doc_id / tenant_id / chunk_type
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量索引（HNSW，查询性能最优）
CREATE INDEX IF NOT EXISTS idx_embedding_hnsw
    ON kb_embeddings USING hnsw (embedding vector_cosine_ops);

-- 元数据查询索引
CREATE INDEX IF NOT EXISTS idx_emb_metadata
    ON kb_embeddings USING GIN (metadata);
```

**pgvector 模式配置**：

```yaml
kb:
  vector-store:
    provider: pgvector
    pgvector:
      table-name: kb_embeddings
      schema-name: public
      dimensions: 1024
      distance-type: COSINE_DISTANCE
      index-type: HNSW
      initialize-schema: true
```

- 向量表与主数据表同库，通过 `id = kb_chunk.id` 外键关联
- pgvector 的 HNSW 索引在小规模（< 10 万向量）下性能与 Milvus 接近
- 无需额外中间件，减少运维复杂度

### 7.3 Milvus Collection 设计（Milvus 模式）

```python
# Milvus Collection Schema (伪代码描述)
Collection: kb_chunks

Fields:
  - vector_id     (VARCHAR, max_length=100, Primary Key)
  - embedding     (FLOAT_VECTOR, dim=1024)         # 维度取决于 Embedding 模型
  - doc_id        (VARCHAR, max_length=36)         # 关联 PG kb_document.id
  - chunk_id      (VARCHAR, max_length=36)         # 关联 PG kb_chunk.id
  - tenant_id     (VARCHAR, max_length=36)         # 租户隔离
  - chunk_type    (VARCHAR, max_length=20)         # TEXT / TABLE / IMAGE
  - is_deleted    (BOOL, default=False)            # 配合 PG 软删除

Index:
  - Type: HNSW
  - Metric: COSINE
  - efConstruction: 200
  - M: 16

Search Parameters:
  - ef: 100
  - metric_type: COSINE
```

### 7.4 Elasticsearch 索引设计

```json
{
  "index": "kb_chunks",
  "mappings": {
    "properties": {
      "chunk_id":    { "type": "keyword" },
      "doc_id":      { "type": "keyword" },
      "content":     { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
      "chunk_type":  { "type": "keyword" },
      "tenant_id":   { "type": "keyword" },
      "page_num":    { "type": "integer" },
      "created_at":  { "type": "date" }
    }
  }
}
```

### 7.5 Redis 缓存策略

| Key Pattern | 数据类型 | 用途 | TTL |
|-------------|---------|------|-----|
| `chat:memory:{sessionId}` | List (JSON) | 多轮对话历史 | 24h |
| `rate:user:{userId}:{minute}` | String (计数器) | 用户级别限流 | 1min |
| `rate:tenant:{tenantId}:{minute}` | String | 租户级别限流 | 1min |
| `prompt:template:{name}:{version}` | String (JSON) | Prompt 模板缓存 | 1h |
| `semantic:cache:{queryHash}` | String (JSON) | 语义缓存命中 | 30min |
| `etl:progress:{docId}` | Hash | ETL 进度追踪 | 24h |
| `token:budget:{tenantId}:{day}` | String (计数器) | Token 预算计数 | 1d |

---

# 第四卷：分阶段落地路线图（执行层）

## 第八章：五阶段实施路线图

### 8.0 路线图总览

```
Phase 1 (W1-W3)     Phase 2 (W4-W7)      Phase 3 (W8-W12)     Phase 4 (W13-W16)    Phase 5 (W17-W24)
───────────────     ───────────────      ────────────────     ─────────────────    ─────────────────
基础设施 + MVP      知识引擎攻坚         Agent + 企业级       运维观测 + 产品化    高级特性 + 持续进化
   P0 闭环          P1 核心竞争力         P2 企业级必备        P2 收尾 + 打磨       P3 智能化升级

  ┌──────┐           ┌─────────┐           ┌──────────┐         ┌──────────┐         ┌────────────┐
  │ MVP  │──────────→│ 核心引擎│──────────→│ Agent编排│────────→│ 可观测性 │────────→│ GraphRAG   │
  │ 验证 │           │ 差异化  │           │ 安全加固 │         │ 运维闭环 │         │ MultiAgent │
  └──────┘           └─────────┘           └──────────┘         └──────────┘         └────────────┘
```

### Phase 1：基础设施与 MVP 验证（第 1-3 周）

**目标**：跑通"文档上传 → 基础切分 → 向量入库 → 单路 RAG 问答"闭环

#### 任务清单（11 项）

| #    | 任务 | 负责模块 | 工时估算 | 验收标准 |
|------|------|---------|---------|---------|
| 1.1  | 搭建 Maven 多模块工程骨架（8 个模块） | 工程基础 | 2d | `mvn clean compile` 通过 |
| 1.2  | 配置 Spring Boot 4.1 + Spring AI 2.0.0 GA BOM | kb-commons | 0.5d | 依赖解析无冲突 |
| 1.3  | 实现 PostgreSQL 核心表（DDL + JPA Entity） | kb-domain | 1.5d | 表创建 + Repository CRUD 验证 |
| 1.4  | 实现基础文档上传 API（MultipartFile → MinIO） | kb-api | 1d | Postman 上传成功 |
| 1.5  | 实现 TikaDocumentReader + TokenTextSplitter 基础 ETL | kb-etl | 2d | PDF/Docx 解析 + 切分验证 |
| 1.6  | 实现 EmbeddingModel 向量化 + VectorStore 写入 | kb-etl | 1d | 向量库中可查向量 |
| 1.7  | 配置 ChatClient + QuestionAnswerAdvisor 基础 RAG | kb-ai-core | 1d | 知识库问答返回正确 |
| 1.8  | 实现基础对话 REST API（同步 + 流式 SSE） | kb-api | 1.5d | curl SSE 流式输出 |
| 1.9  | 实现统一 API 响应格式 + 全局异常处理 | kb-api | 0.5d | 错误响应格式统一 |
| 1.10 | 搭建 Vue3 前端基础工程 + 对话界面 | 前端 | 2d | 可对话 + 流式渲染 |
| 1.11 | 配置基础日志（Logback JSON 格式） | 工程基础 | 0.5d | 结构化日志输出 |

#### 交付物

- [ ] 可运行的多模块 Maven 工程
- [ ] 文档上传 API（PDF/Docx/MD/TXT）
- [ ] 基础 ETL 管道（Tika 解析 + Token 切分 + Milvus 入库）
- [ ] 单路 RAG 问答 API（同步 + SSE 流式）
- [ ] 前端基础对话界面
- [ ] 数据库 DDL 脚本
- [ ] Postman Collection 基础接口

#### Phase 1 验收标准

| 指标 | 目标值 |
|------|--------|
| 文档上传→入库可用 | PDF(电子版)/Docx/MD/TXT |
| 单文档（50页）解析入库时间 | < 3 分钟 |
| 基础 RAG 问答准确率（简单事实型） | > 70% |
| SSE 首 Token 延迟 | < 2s |

---

### Phase 2：知识引擎攻坚（第 4-7 周）【攻坚期】

**目标**：解决复杂文档解析与专有名词检索痛点

#### 任务清单（15 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 |
|---|------|---------|---------|---------|
| 2.1 | 实现 SmartOcrRoutingReader（文本密度探测 + 动态路由） | kb-etl | 3d | 扫描件自动走 OCR |
| 2.2 | 对接阿里云/百度 OCR API（返回 HTML 结构化文本） | kb-infrastructure | 2d | OCR 成功解析扫描件 |
| 2.3 | 实现 HtmlProtectingSplitter（JSoup 解析 + 表格/图片保护） | kb-etl | 3d | 表格 Chunk 完整不碎片 |
| 2.4 | 实现 PG-Milvus 解耦存储（分层知识管理） | kb-etl/kb-domain | 2d | Document→Section→Chunk→Vector 四级关联 |
| 2.5 | 部署 Elasticsearch + 创建 kb_chunks 索引 | kb-infrastructure | 1d | ES 索引可用 |
| 2.6 | 实现 ES BM25 全文检索服务 | kb-ai-core | 2d | 关键词检索命中验证 |
| 2.7 | 实现 HybridRetrievalService（虚拟线程并行召回） | kb-ai-core | 2d | 双路并行，延迟低于串行 |
| 2.8 | 实现 RrfFusionReranker 融合排序算法 | kb-ai-core | 1.5d | RRF 融合后专有名词命中提升 |
| 2.9 | 集成重排序模型（BGE-Reranker-v2 本地部署 / Cohere API） | kb-ai-core | 2d | 重排序后 Top-3 精度提升 |
| 2.10 | 实现 PrefetchRagAdvisor（CallAround + StreamAround） | kb-ai-core | 3d | 证据自动注入 + 溯源数据透传 |
| 2.11 | 实现 GroundingAdvisor（回答引用标注 + 元数据附加） | kb-ai-core | 2d | 回答带 [ref-N] 标注 |
| 2.12 | 实现 SSE TRACE 事件推送（检索详情 + 溯源数据） | kb-api | 1.5d | 流结束推送完整溯源 |
| 2.13 | 实现异步 ETL 管道（@Async + WebSocket 进度推送） | kb-etl | 2d | 大文件不阻塞 + 前端进度条 |
| 2.14 | 前端检索调试台（展示各路得分 + Chunk 原文） | 前端 | 3d | 可查看每个 Chunk 的向量分/BM25分/融合分 |
| 2.15 | 前端 Chunk 观测台 + 文档管理界面 | 前端 | 2d | 文档列表 + Chunk 列表可视化 |

#### 交付物

- [ ] 双链路文档解析引擎（Tika + OCR 路由）
- [ ] HtmlProtectingSplitter 保护式切分器
- [ ] 混合检索引擎（向量 + BM25 + RRF 融合 + 重排序）
- [ ] PrefetchRagAdvisor + GroundingAdvisor
- [ ] SSE 流式对话（Token + TRACE 事件）
- [ ] 异步 ETL 管道（进度实时推送）
- [ ] 前端检索调试台 + Chunk 观测台

#### Phase 2 验收标准

| 指标 | 目标值 |
|------|--------|
| 扫描件 PDF OCR 解析可用率 | > 85% |
| 表格 Chunk 结构完整性 | > 90%（表格不跨 Chunk 断裂） |
| 混合检索 Top-5 命中率 | > 85% |
| 专有名词/标题词命中率 | > 90% |
| 流式首 Token 延迟 (TTFT) | < 1.5s |
| 单次检索延迟（10万级 Chunk） | < 200ms |

---

### Phase 3：Agent 编排与企业级特性（第 8-12 周）

**目标**：实现带溯源的流式 Agent 对话与企业级安全管控

#### 任务清单（18 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 |
|---|------|---------|---------|---------|
| 3.1 | 配置 RedisChatMemory + MessageChatMemoryAdvisor | kb-ai-core | 1.5d | 多轮对话上下文记忆正常 |
| 3.2 | 实现 SmartRoutingChatModel（多模型路由 + Fallback） | kb-ai-core | 3d | 主模型故障自动切换备用 |
| 3.3 | 注册 @Tool 工具（OA 查询 / ERP 数据 / 数据库查询） | kb-ai-core | 3d | ToolCallingAdvisor 正确编排 |
| 3.4 | 实现 ToolCallingAdvisor 安全沙箱（读自动/写审批） | kb-ai-core | 2d | 写操作需 ToolContext 确认 |
| 3.5 | 实现 InputSanitizeAdvisor（PII 脱敏 + 注入检测） | kb-ai-core | 2d | 手机号/身份证自动掩码 |
| 3.6 | 实现 OutputGuardrailAdvisor（敏感词/竞品/幻觉拦截） | kb-ai-core | 2d | 违规输出被拦截替换 |
| 3.7 | 实现 RateLimitAdvisor（Redisson 令牌桶） | kb-ai-core | 1.5d | 超限请求返回 429 |
| 3.8 | 实现 TokenBudgetAdvisor（成本追踪 + 预算控制） | kb-ai-core | 1.5d | 超额自动拦截 |
| 3.9 | 实现 AuthAdvisor（JWT 鉴权 + 用户/租户提取） | kb-ai-core | 2d | 无 Token 请求被拒绝 |
| 3.10 | 实现多租户数据隔离（FilterExpression 注入 tenant_id） | kb-ai-core | 2d | 跨租户数据不可见 |
| 3.11 | 实现 RBAC 权限过滤（部门/角色级文档可见性） | kb-ai-core | 2d | 无权限文档不可检索 |
| 3.12 | 实现 AuditTraceAdvisor（全链路审计日志落库） | kb-ai-core | 2d | 每次问答完整记录 kb_audit_log |
| 3.13 | 实现 AiBusinessMetrics（反馈/命中率/工具成功率指标） | kb-ai-core | 1.5d | Prometheus 可采集 |
| 3.14 | 组装企业级 ChatClient（全部 8 个 Advisor） | kb-ai-core | 1d | Advisor 链顺序正确 |
| 3.15 | 前端 Agent 对话窗全功能（多轮/溯源卡片/工具状态） | 前端 | 3d | 溯源引用可点击查看原文 |
| 3.16 | 前端知识库权限管理界面 | 前端 | 2d | 租户/角色权限配置 |
| 3.17 | 实现用户反馈收集 API（点赞/点踩 + 期望回答） | kb-api | 1d | kb_feedback 落库 |
| 3.18 | 集成测试：完整 Advisor 链端到端验证 | kb-eval | 2d | Testcontainers 集成测试通过 |

#### 交付物

- [ ] 企业级 ChatClient（8 个 Advisor 完整链）
- [ ] SmartRoutingChatModel 多模型路由
- [ ] @Tool 工具集成（OA/ERP/数据库）
- [ ] 输入输出安全护栏
- [ ] 多租户 + RBAC 权限隔离
- [ ] 全链路审计日志
- [ ] AiBusinessMetrics 业务指标
- [ ] 前端 Agent 全功能对话窗口
- [ ] 用户反馈收集闭环
- [ ] Testcontainers 集成测试套件

#### Phase 3 验收标准

| 指标 | 目标值 |
|------|--------|
| 多轮对话（10轮）上下文连贯性 | > 90% |
| 模型 Failover 切换时间 | < 5s |
| PII 脱敏准确率 | > 99% |
| Prompt 注入拦截率 | > 95% |
| 多租户数据隔离 | 0 泄露 |
| 审计日志完整率 | 100% |

---

### Phase 4：运维观测与产品化（第 13-16 周）

**目标**：打造可度量、可迭代的 AI 运营闭环

#### 任务清单（14 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 |
|---|------|---------|---------|---------|
| 4.1 | 接入 OpenTelemetry Java Agent（自动埋点） | 工程基础 | 1.5d | Trace ID 全链路透传 |
| 4.2 | 配置 Prometheus 指标采集 + 告警规则 | 部署 | 2d | 延迟超阈值自动告警 |
| 4.3 | 搭建 Grafana 4 个 Dashboard | 部署 | 2d | 可视化监控大盘 |
| 4.4 | 实现 Chunk CRUD 运维 API（编辑/删除/恢复） | kb-admin | 2d | 编辑后异步更新向量 |
| 4.5 | 实现文档级物理删除 + 级联清理 | kb-admin | 1d | PG CASCADE + Milvus delete |
| 4.6 | 实现索引全量/增量重建 API | kb-admin | 2d | 异步批处理，进度可查 |
| 4.7 | 实现 PromptTemplateManager（Redis 缓存 + DB 持久化） | kb-ai-core | 2d | 模板 CRUD + 版本管理 |
| 4.8 | 实现问答日志查询 + Bad Case 分析面板 | kb-admin + 前端 | 3d | 可按时间/用户/会话检索 |
| 4.9 | 实现文档解析状态 + 知识库统计 API | kb-api | 1d | 仪表盘数据接口 |
| 4.10 | Kubernetes 部署配置（Deployment + Service + HPA + Secrets） | 部署 | 3d | K8s 集群运行正常 |
| 4.11 | Milvus 分布式集群部署（etcd + MinIO + Pulsar） | 部署 | 2d | 高可用向量检索 |
| 4.12 | 压力测试与性能调优（JMeter/Gatling） | 测试 | 2d | 100 QPS 稳定运行 |
| 4.13 | 前端运维审计看板（仪表盘 + 日志查询 + Bad Case） | 前端 | 3d | 可视化运维面板 |
| 4.14 | 编写运维手册 + API 文档 + 用户使用手册 | 文档 | 2d | 完整文档交付 |

#### 交付物

- [ ] OpenTelemetry 全链路追踪
- [ ] Prometheus + Grafana 监控体系（4 个 Dashboard）
- [ ] Chunk CRUD + 索引重建运维 API
- [ ] Prompt 版本化管理
- [ ] Bad Case 分析工作流
- [ ] Kubernetes 生产部署配置
- [ ] Milvus 分布式集群
- [ ] 压力测试报告
- [ ] 运维手册 + API 文档 + 用户手册

#### Phase 4 验收标准

| 指标 | 目标值 |
|------|--------|
| OpenTelemetry Trace 覆盖率 | 100% |
| 生产环境可用性 | > 99.9% |
| 100 QPS 下 P95 延迟 | < 3s |
| 10万 Chunk 索引重建时间 | < 30min |
| Bad Case 可回溯率 | 100% |

---

### Phase 5：高级特性与持续进化（第 17-24 周）

**目标**：GraphRAG、Multi-Agent、智能路由、反馈闭环

#### 任务清单（12 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 |
|---|------|---------|---------|---------|
| 5.1 | 部署 Neo4j + 知识图谱构建管道 | kb-infrastructure/kb-etl | 5d | 实体关系自动抽取入库 |
| 5.2 | 实现 GraphRAG 混合检索（Vector + Graph 双引擎） | kb-ai-core | 5d | 多跳推理问题可正确回答 |
| 5.3 | 实现 Multi-Agent Orchestrator（主Agent+子Agent 模式） | kb-ai-core | 5d | 复杂任务自动分解执行 |
| 5.4 | 实现查询意图识别 + 自适应路由（分类器模型） | kb-ai-core | 3d | 不同意图分流到不同策略 |
| 5.5 | 实现多知识库动态路由（不同领域独立 VectorStore） | kb-ai-core | 3d | 按领域自动选择知识库 |
| 5.6 | 实现 Semantic Cache（Redis RediSearch + 语义相似度） | kb-ai-core | 3d | 相似问题缓存命中率 > 30% |
| 5.7 | 构建 Golden Dataset（200+ 问答对） | kb-eval | 3d | 测试集覆盖主要场景 |
| 5.8 | 实现 LLM-as-Judge 自动评估管道 | kb-eval | 3d | Context Relevance + Faithfulness 自动评分 |
| 5.9 | 实现 A/B 测试框架（Prompt 效果对比） | kb-eval/kb-ai-core | 3d | 双版本效果量化对比 |
| 5.10 | 实现反馈闭环导出管道（JSONL SFT 格式） | kb-admin | 2d | 可用于模型微调的数据导出 |
| 5.11 | 实现 MCP Server 宿主（知识库对外暴露为 MCP 服务） | kb-api | 3d | 第三方 Agent 可调用知识库 |
| 5.12 | 企业微信/钉钉集成适配 | kb-api | 3d | 工作 IM 内直接问答 |

#### Phase 5 验收标准

| 指标 | 目标值 |
|------|--------|
| GraphRAG 多跳推理准确率 | > 80% |
| Multi-Agent 任务完成率 | > 85% |
| Semantic Cache 命中率 | > 30% |
| 自动评估 CI 集成 | 每 PR 自动运行 |
| MCP Server 兼容性 | 标准 MCP Client 可正常调用 |

---

# 第五卷：核心模块技术实现（实现层）

## 第九章：知识入库 ETL 管道

### 9.0 ETL 异步执行器配置

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

### 9.1 SmartOcrRoutingReader

```java
package com.enterprise.kb.etl.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 智能文档解析路由器：基于文本密度探测动态选择解析路径
 * 
 * 核心逻辑：
 * - 电子文档（文本密度 > 阈值）→ Tika 原生解析
 * - 扫描件（文本密度 < 阈值）→ OCR API 解析
 * - 含复杂表格 → OCR API（保留表格结构）
 */
@Component
public class SmartOcrRoutingReader implements DocumentReader {

    private static final double TEXT_DENSITY_THRESHOLD = 0.05; // 文本密度阈值
    private static final int PROBE_PAGES = 3;                  // 探测页数
    
    private final OcrApiClient ocrApiClient;
    private final TextDensityAnalyzer densityAnalyzer;
    
    private Resource resource;
    private Map<String, Object> customMetadata;

    @Override
    public List<Document> get() {
        // 1. 探测文本密度
        double density = densityAnalyzer.analyze(resource, PROBE_PAGES);
        boolean hasComplexTables = densityAnalyzer.hasComplexTables(resource);
        
        // 2. 路由决策
        if (density < TEXT_DENSITY_THRESHOLD || hasComplexTables) {
            return parseViaOcr(resource);
        }
        return parseViaTika(resource);
    }
    
    private List<Document> parseViaTika(Resource resource) {
        TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
        List<Document> docs = tikaReader.get();
        // 注入元数据
        docs.forEach(doc -> {
            doc.getMetadata().put("parse_route", "NATIVE");
            if (customMetadata != null) {
                doc.getMetadata().putAll(customMetadata);
            }
        });
        return docs;
    }
    
    private List<Document> parseViaOcr(Resource resource) {
        // 调用 OCR API，返回带 HTML 标签的结构化文本
        OcrResult result = ocrApiClient.parseToHtml(resource);
        Document doc = new Document(result.getHtmlContent());
        doc.getMetadata().put("parse_route", "OCR");
        doc.getMetadata().put("table_count", result.getTableCount());
        doc.getMetadata().put("image_count", result.getImageCount());
        if (customMetadata != null) {
            doc.getMetadata().putAll(customMetadata);
        }
        return List.of(doc);
    }
    
    // Builder 模式
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        // ... setter 方法
        public SmartOcrRoutingReader build() { /* ... */ }
    }
}
```

### 9.2 HtmlProtectingSplitter

```java
package com.enterprise.kb.etl.transformer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * HTML 结构保护式切分器
 * 
 * 保护策略：
 * - <table> 块 → 独立 Chunk（chunk_type=TABLE）
 * - <img> 块 → 独立 Chunk（chunk_type=IMAGE）
 * - 纯文本 → TokenTextSplitter 常规切分
 * - 保护块前后的文本 → 与最近的文本 Chunk 合并（避免孤立碎片）
 */
@Component
public class HtmlProtectingSplitter implements DocumentTransformer {

    private final TokenTextSplitter textSplitter;
    
    // 需要保护的 HTML 标签
    private static final List<String> PROTECTED_TAGS = List.of("table", "img");
    // 表格最小字符数（小于此值的表格合并到文本中）
    private static final int MIN_TABLE_CHARS = 30;
    
    public HtmlProtectingSplitter() {
        this.textSplitter = new TokenTextSplitter(
            800,   // defaultChunkSize
            200,   // minChunkSizeChars
            10,    // minChunkLengthToEmbed
            5,     // maxNumChunks
            true   // keepSeparator
        );
    }
    
    @Override
    public List<Document> apply(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        
        for (Document doc : documents) {
            String html = doc.getText();
            if (!containsProtectedTags(html)) {
                // 无保护块，直接常规切分
                result.addAll(textSplitter.apply(List.of(doc)));
                continue;
            }
            
            // 使用 JSoup 解析 HTML 结构
            org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html);
            List<ContentBlock> blocks = extractBlocks(jsoupDoc.body());
            
            // 按文档顺序处理内容块
            int chunkIndex = 0;
            StringBuilder textBuffer = new StringBuilder();
            
            for (ContentBlock block : blocks) {
                switch (block.type()) {
                    case TEXT -> textBuffer.append(block.content()).append("\n");
                    
                    case TABLE -> {
                        // 先处理缓冲区中积累的文本
                        if (!textBuffer.isEmpty()) {
                            result.addAll(splitAndIndex(textBuffer.toString(), 
                                doc.getMetadata(), chunkIndex));
                            chunkIndex += countChunks(textBuffer.toString());
                            textBuffer.setLength(0);
                        }
                        // 表格作为独立 Chunk
                        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                        meta.put("chunk_type", "TABLE");
                        meta.put("original_html", block.content());
                        result.add(new Document(block.content(), meta));
                        chunkIndex++;
                    }
                    
                    case IMAGE -> {
                        if (!textBuffer.isEmpty()) {
                            result.addAll(splitAndIndex(textBuffer.toString(), 
                                doc.getMetadata(), chunkIndex));
                            chunkIndex += countChunks(textBuffer.toString());
                            textBuffer.setLength(0);
                        }
                        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
                        meta.put("chunk_type", "IMAGE");
                        meta.put("original_html", block.content());
                        result.add(new Document(block.content(), meta));
                        chunkIndex++;
                    }
                }
            }
            
            // 处理剩余文本
            if (!textBuffer.isEmpty()) {
                result.addAll(splitAndIndex(textBuffer.toString(), 
                    doc.getMetadata(), chunkIndex));
            }
        }
        
        return result;
    }
    
    private List<ContentBlock> extractBlocks(Element body) {
        List<ContentBlock> blocks = new ArrayList<>();
        // 遍历子节点，识别保护块
        for (Element child : body.children()) {
            if (child.tagName().equals("table")) {
                String tableHtml = child.outerHtml();
                if (tableHtml.length() >= MIN_TABLE_CHARS) {
                    blocks.add(new ContentBlock(ContentType.TABLE, tableHtml));
                } else {
                    blocks.add(new ContentBlock(ContentType.TEXT, child.text()));
                }
            } else if (child.tagName().equals("img")) {
                blocks.add(new ContentBlock(ContentType.IMAGE, child.outerHtml()));
            } else {
                blocks.add(new ContentBlock(ContentType.TEXT, child.text()));
            }
        }
        return blocks;
    }
    
    private boolean containsProtectedTags(String html) {
        return PROTECTED_TAGS.stream().anyMatch(tag -> 
            html.toLowerCase().contains("<" + tag));
    }
    
    // 内部类
    private enum ContentType { TEXT, TABLE, IMAGE }
    private record ContentBlock(ContentType type, String content) {}
}
```

### 9.3 DocumentEtlPipeline Builder

```java
package com.enterprise.kb.etl.pipeline;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * ETL 管道 Builder —— 将 Reader → Transformer → Writer 编排为完整管道
 */
@Service
public class DocumentEtlPipeline {
    
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final KbChunkRepository chunkRepository;
    
    public DocumentEtlPipeline(EmbeddingModel embeddingModel,
                               VectorStore vectorStore,
                               KbChunkRepository chunkRepository) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.chunkRepository = chunkRepository;
    }
    
    @Async("etlExecutor")
    public void execute(String docId, DocumentReader reader,
                        List<DocumentTransformer> transformers,
                        Consumer<EtlProgress> progressCallback) {
        
        EtlProgress progress = new EtlProgress(docId, EtlStage.READING, 0);
        progressCallback.accept(progress);
        
        // Stage 1: Read
        progress.setStage(EtlStage.READING);
        List<Document> rawDocs = reader.get();
        progress.setDocumentCount(rawDocs.size());
        progressCallback.accept(progress);
        
        // Stage 2: Transform
        progress.setStage(EtlStage.TRANSFORMING);
        List<Document> docs = rawDocs;
        for (DocumentTransformer transformer : transformers) {
            docs = transformer.apply(docs);
        }
        progress.setChunkCount(docs.size());
        progressCallback.accept(progress);
        
        // Stage 3: Persist to PG
        progress.setStage(EtlStage.PERSISTING);
        List<KbChunkEntity> entities = persistToPg(docId, docs);
        
        // Stage 4: Embed & Write to Milvus（利用 VectorStore 内置的 EmbeddingModel 一次批量写入）
        progress.setStage(EtlStage.EMBEDDING);
        List<Document> vectorDocs = entities.stream()
            .map(e -> new Document(e.getContent(),
                Map.of("chunk_id", e.getId(),
                       "doc_id", docId,
                       "chunk_type", e.getChunkType(),
                       "tenant_id", e.getTenantId(),
                       "is_deleted", e.isDeleted())))
            .toList();
        
        // Spring AI VectorStore.add() 批量接受 Document 列表，内部自动向量化
        List<String> vectorIds = vectorStore.add(vectorDocs);
        for (int i = 0; i < entities.size(); i++) {
            entities.get(i).setVectorId(vectorIds.get(i));
        }
        chunkRepository.saveAll(entities);
        
        progress.setProcessedChunks(entities.size());
        progress.setPercentage(100);
        progressCallback.accept(progress);
        
        progress.setStage(EtlStage.COMPLETED);
        progress.setPercentage(100);
        progressCallback.accept(progress);
    }
    
    private List<KbChunkEntity> persistToPg(String docId, List<Document> chunks) {
        List<KbChunkEntity> entities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            KbChunkEntity entity = new KbChunkEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setDocId(docId);
            entity.setChunkIndex(i);
            entity.setContent(chunk.getText());
            entity.setPageNum((Integer) chunk.getMetadata().get("page_num"));
            entity.setChunkType((String) chunk.getMetadata().getOrDefault("chunk_type", "TEXT"));
            entities.add(entity);
        }
        chunkRepository.saveAll(entities);
        return entities;
    }
}

/**
 * ETL 进度追踪对象（建议拆分到独立文件 EtlProgress.java）
 */
@Data
class EtlProgress {
    private String docId;
    private EtlStage stage;
    private int documentCount;
    private int chunkCount;
    private int processedChunks;
    private double percentage;
}

enum EtlStage { READING, TRANSFORMING, PERSISTING, EMBEDDING, COMPLETED, FAILED }
```

---

## 第十章：混合检索引擎

### 10.1 HybridRetrievalService（虚拟线程并发）

```java
package com.enterprise.kb.ai.retriever;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpression;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.StructuredTaskScope;

/**
 * 混合检索服务 —— 利用 Java 21 虚拟线程并行召回
 * 
 * 容错策略：双路并行，收集成功路径的结果。
 * - Milvus 和 ES 任一路成功即可返回结果
 * - 两路都失败才降级
 */
@Service
@Slf4j
public class HybridRetrievalService {
    
    private final VectorStore milvusVectorStore;
    private final ElasticsearchClient esClient;
    private final RrfFusionReranker rrfReranker;
    private final RerankClient rerankClient;
    
    private static final int TOP_K_MULTIPLIER = 2;
    
    /**
     * 混合检索主方法
     */
    public List<RetrievalResult> hybridSearch(String query, int topK, 
                                               Filter.Expression filterExpression,
                                               String tenantId) {
        int recallSize = topK * TOP_K_MULTIPLIER;
        
        List<Document> vectorResults = List.of();
        List<EsHit> bm25Results = List.of();
        
        // 并行执行双路检索，各自容错
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var vectorFuture = executor.submit(() -> {
                try {
                    SearchRequest request = SearchRequest.builder()
                        .query(query)
                        .topK(recallSize)
                        .similarityThreshold(0.5)
                        .filterExpression(filterExpression)
                        .build();
                    return milvusVectorStore.similaritySearch(request);
                } catch (Exception e) {
                    log.warn("向量检索失败: {}", e.getMessage());
                    return List.<Document>of();
                }
            });
            
            var bm25Future = executor.submit(() -> {
                try {
                    return esClient.search(query, recallSize, filterExpression, tenantId);
                } catch (Exception e) {
                    log.warn("BM25 检索失败: {}", e.getMessage());
                    return List.<EsHit>of();
                }
            });
            
            vectorResults = vectorFuture.get(5, TimeUnit.SECONDS);
            bm25Results = bm25Future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("混合检索超时或全部失败: {}", e.getMessage());
        }
        
        // 双路结果 RRF 融合
        List<RetrievalResult> fused = rrfReranker.fuse(vectorResults, bm25Results, recallSize);
        
        // 重排序
        if (rerankClient.isAvailable() && !fused.isEmpty()) {
            fused = rerankClient.rerank(query, fused, topK);
        } else {
            fused = fused.subList(0, Math.min(topK, fused.size()));
        }
        
        return fused;
    }
}
```

### 10.2 RrfFusionReranker

```java
package com.enterprise.kb.ai.retriever;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RRF (Reciprocal Rank Fusion) 融合排序器
 * 
 * 公式：RRF_score(d) = Σ 1 / (k + rank_i(d))
 * 其中 k=60（标准常数），rank_i 是文档在第 i 个排序列表中的排名
 * 
 * 优势：
 * - 不需要分数归一化
 * - 对离群分数不敏感
 * - 简单高效
 */
@Component
public class RrfFusionReranker {
    
    private static final int K = 60;
    
    public List<RetrievalResult> fuse(List<Document> vectorHits, 
                                       List<EsHit> bm25Hits, 
                                       int topK) {
        
        Map<String, RetrievalResult> resultMap = new LinkedHashMap<>();
        
        // 计算向量检索 RRF 分数
        for (int i = 0; i < vectorHits.size(); i++) {
            Document doc = vectorHits.get(i);
            String id = doc.getId();
            double rrfScore = 1.0 / (K + i + 1);
            
            RetrievalResult result = resultMap.computeIfAbsent(id, 
                k -> RetrievalResult.fromVectorDoc(doc));
            result.addVectorScore(rrfScore);
            result.setVectorRank(i + 1);
        }
        
        // 计算 BM25 检索 RRF 分数
        for (int i = 0; i < bm25Hits.size(); i++) {
            EsHit hit = bm25Hits.get(i);
            String id = hit.id();
            double rrfScore = 1.0 / (K + i + 1);
            
            RetrievalResult result = resultMap.computeIfAbsent(id, 
                k -> RetrievalResult.fromEsHit(hit));
            result.addBm25Score(rrfScore);
            result.setBm25Rank(i + 1);
        }
        
        // 按融合总分降序排序
        return resultMap.values().stream()
            .sorted(Comparator.comparingDouble(RetrievalResult::getFusionScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
}

/**
 * 检索结果统一模型（建议拆分到独立文件 RetrievalResult.java）
 */
@Data
class RetrievalResult {
    private String chunkId;
    private String docId;
    private String content;
    private String fileName;
    private Integer pageNum;
    private String chunkType;
    
    // 各维度得分
    private Double vectorScore;
    private Double bm25Score;
    private Double fusionScore;
    private Double rerankScore;
    
    // 各维度排名
    private Integer vectorRank;
    private Integer bm25Rank;
    
    public void addVectorScore(double score) {
        this.vectorScore = score;
        recomputeFusion();
    }
    
    public void addBm25Score(double score) {
        this.bm25Score = score;
        recomputeFusion();
    }
    
    private void recomputeFusion() {
        this.fusionScore = (vectorScore != null ? vectorScore : 0) 
                         + (bm25Score != null ? bm25Score : 0);
    }
    
    public static RetrievalResult fromVectorDoc(Document doc) {
        RetrievalResult r = new RetrievalResult();
        r.chunkId = doc.getId();
        r.content = doc.getText();
        r.chunkType = (String) doc.getMetadata().getOrDefault("chunk_type", "TEXT");
        r.pageNum = (Integer) doc.getMetadata().get("page_num");
        return r;
    }
    
    public static RetrievalResult fromEsHit(EsHit hit) {
        RetrievalResult r = new RetrievalResult();
        r.chunkId = hit.id();
        r.content = hit.content();
        r.chunkType = hit.chunkType();
        return r;
    }
}
```

### 10.3 Elasticsearch BM25 检索实现

```java
package com.enterprise.kb.ai.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ES 检索结果记录
 */
public record EsHit(String id, String content, String chunkType, double score) {}

/**
 * ES 文档映射类
 */
@Data
public class EsChunkDoc {
    private String chunkId;
    private String content;
    private String chunkType;
    private String docId;
}

@Component
public class ElasticsearchRetrievalService {
    
    private final ElasticsearchClient esClient;
    private static final String INDEX_NAME = "kb_chunks";
    
    public List<EsHit> search(String query, int topK, Filter.Expression filter, String tenantId) {
        var searchRequest = co.elastic.clients.elasticsearch.core.SearchRequest.of(s -> s
            .index(INDEX_NAME)
            .query(q -> q
                .bool(b -> {
                    b.must(m -> m.match(mm -> mm.field("content").query(query)));
                    // 注入权限过滤（tenantId 从 Advisor 上下文传入，不是从 Filter.Expression 提取）
                    b.filter(f -> f.term(t -> t.field("tenant_id").value(tenantId)));
                    return b;
                })
            )
            .size(topK)
            .sort(sort -> sort.score(sc -> sc))
        );
        
        SearchResponse<EsChunkDoc> response = esClient.search(searchRequest, EsChunkDoc.class);
        
        return response.hits().hits().stream()
            .map(hit -> new EsHit(
                hit.source().getChunkId(),
                hit.source().getContent(),
                hit.source().getChunkType(),
                hit.score() != null ? hit.score() : 0.0
            ))
            .toList();
    }
}
```

### 10.4 查询改写服务

```java
package com.enterprise.kb.ai.retriever;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 查询改写 —— 结合历史对话上下文，将用户的简短/指代性查询改写为独立完整的检索查询
 * 
 * 例如：
 * - 用户: "它的配置参数是什么？" (上文在讨论某个产品)
 * - 改写后: "XX产品的安装配置参数是什么？"
 */
@Service
public class QueryRewriter {
    
    private final ChatClient rewriteClient;
    
    private static final String REWRITE_PROMPT = """
        你是一个查询改写助手。根据对话历史和用户最新问题，生成一个独立、完整、适合用于知识库检索的查询。
        
        对话历史：
        %s
        
        用户最新问题：%s
        
        请输出改写后的检索查询（只输出查询文本，不要加任何解释）：""";
    
    public String rewrite(String userQuery, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return userQuery; // 首轮对话无需改写
        }
        
        String historyText = history.stream()
            .map(m -> m.role() + ": " + m.content())
            .collect(Collectors.joining("\n"));
        
        String prompt = String.format(REWRITE_PROMPT, historyText, userQuery);
        return rewriteClient.prompt().user(prompt).call().content().trim();
    }
}
```

### 10.5 重排序集成

```java
package com.enterprise.kb.ai.retriever;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 重排序客户端 —— 支持本地 BGE-Reranker-v2 和 Cohere Rerank API 两种模式
 */
@Component
public class RerankClient {
    
    private final RestClient restClient;
    private final String rerankEndpoint;
    private final boolean available;
    
    public RerankClient(@Value("${rag.rerank.endpoint:}") String endpoint) {
        this.available = (endpoint != null && !endpoint.isEmpty());
        this.rerankEndpoint = endpoint;
        this.restClient = RestClient.create();
    }
    
    public boolean isAvailable() { return available; }
    
    /**
     * 对检索结果进行重排序
     */
    public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
        if (!available || candidates.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        
        // 调用 Cohere Rerank API (或本地 BGE-Reranker)
        var requestBody = Map.of(
            "model", "rerank-multilingual-v3.0",  // Cohere Rerank
            "query", query,
            "documents", candidates.stream().map(RetrievalResult::getContent).toList(),
            "top_n", topK
        );
        
        RerankResponse response = restClient.post()
            .uri(rerankEndpoint)
            .body(requestBody)
            .retrieve()
            .body(RerankResponse.class);
        
        // 将重排序分数映射回 RetrievalResult
        for (int i = 0; i < response.results().size(); i++) {
            RerankResult rr = response.results().get(i);
            candidates.get(rr.index()).setRerankScore(rr.relevanceScore());
            candidates.get(rr.index()).setRerankRank(i + 1);
        }
        
        return candidates.stream()
            .filter(r -> r.getRerankScore() != null)
            .sorted(Comparator.comparingDouble(RetrievalResult::getRerankScore).reversed())
            .limit(topK)
            .toList();
    }
    
    record RerankResponse(List<RerankResult> results) {}
    record RerankResult(int index, double relevanceScore) {}
}
```

### 11.1 PrefetchRagAdvisor（核心 Advisor）

```java
package com.enterprise.kb.ai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Prefetch RAG Advisor - Spring AI 2.0 BaseAdvisor 最佳实践
 * 
 * 继承 BaseAdvisor，重写 before() 方法，在 LLM 调用前完成知识库检索和证据注入。
 * BaseAdvisor 自动为同步调用（adviseCall）和流式调用（adviseStream）统一调度 before/after。
 */
@Component
public class PrefetchRagAdvisor extends BaseAdvisor {

    private final HybridRetrievalService retrievalService;
    private static final int DEFAULT_TOP_K = 5;

    // System Prompt 模板
    private static final String GROUNDING_PROMPT = """
        你是企业知识库专家。必须且只能基于【参考资料】回答问题。
        
        【回答规则】
        1. 若资料中包含相关信息，请准确回答，每个事实性陈述使用 [ref-N] 格式标注来源
        2. 若资料中无相关信息，请明确回答："知识库中未找到相关信息，建议您补充相关文档或换个方式提问"
        3. 若资料提供的信息不足以完全回答问题，请说明已有信息并指出缺失部分
        4. 不要编造、猜测或使用外部知识
        
        【参考资料】
        %s
        """;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 1. 提取用户查询
        String userQuery = request.userText();

        // 2. 执行混合检索
        String tenantId = (String) request.context().get("tenant_id");
        Filter.Expression filter = buildSecurityFilter(request.context());
        List<RetrievalResult> results = retrievalService.hybridSearch(
            userQuery, DEFAULT_TOP_K, filter, tenantId);

        // 3. 构建带溯源标记的证据上下文
        String evidence = buildGroundedEvidence(results);
        String enhancedSystem = String.format(GROUNDING_PROMPT, evidence);

        // 4. 增强请求（注入证据到 System Prompt，透传溯源元数据）
        Map<String, Object> context = new java.util.HashMap<>(request.context());
        context.put("rag_trace", results);
        context.put("retrieval_count", results.size());
        context.put("top_score", results.isEmpty() ? 0 : results.get(0).getFusionScore());

        return ChatClientRequest.from(request)
            .systemText(enhancedSystem)
            .context(context)
            .build();
    }

    private String buildGroundedEvidence(List<RetrievalResult> results) {
        if (results.isEmpty()) {
            return "（知识库中暂无相关参考资料）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append(String.format(
                "[ref-%d] (来源: %s, 页码: %d, 类型: %s, 融合分: %.2f)\n%s\n\n",
                i + 1,
                r.getFileName() != null ? r.getFileName() : "未知",
                r.getPageNum() != null ? r.getPageNum() : 0,
                r.getChunkType(),
                r.getFusionScore(),
                r.getContent()
            ));
        }
        return sb.toString();
    }

    private Filter.Expression buildSecurityFilter(Map<String, Object> context) {
        String tenantId = (String) context.get("tenant_id");
        @SuppressWarnings("unchecked")
        List<String> allowedDocIds = (List<String>) context.get("allowed_doc_ids");
        @SuppressWarnings("unchecked")
        List<String> allowedDeptIds = (List<String>) context.get("allowed_dept_ids");

        var b = new FilterExpressionBuilder();

        // 1. 租户隔离：强制注入 tenant_id
        Filter.Expression filter = b.eq("tenant_id", tenantId).build();

        // 2. 文档级权限
        if (allowedDocIds != null && !allowedDocIds.isEmpty()) {
            filter = b.and(filter, b.in("doc_id", allowedDocIds.toArray()).build()).build();
        }

        // 3. 部门级权限
        if (allowedDeptIds != null && !allowedDeptIds.isEmpty()) {
            filter = b.and(filter, b.in("dept_id", allowedDeptIds.toArray()).build()).build();
        }

        // 4. 软删除过滤
        filter = b.and(filter, b.eq("is_deleted", false).build()).build();

        return filter;
    }

    @Override
    public int getOrder() {
        return 500; // RAG 在记忆(400)之后，工具调用(1000)之前
    }
}
```

### 11.2 AgentChatClientConfig

```java
package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 企业级 ChatClient 装配
 * 
 * Order 链（由各 Advisor 的 getOrder() 决定）：
 * 10  → TokenBudgetAdvisor
 * 50  → AuditTraceAdvisor
 * 100 → RateLimitAdvisor
 * 110 → OutputGuardrailAdvisor
 * 200 → AuthAdvisor
 * 300 → InputSanitizeAdvisor
 * 400 → MessageChatMemoryAdvisor
 * 500 → PrefetchRagAdvisor ★ 核心
 * 1000→ ToolCallingAdvisor
 */
@Configuration
public class AgentChatClientConfig {
    
    @Bean
    public ChatClient knowledgeAgentChatClient(
            ChatClient.Builder builder,
            SmartRoutingChatModel routingChatModel,
            ChatMemory redisChatMemory,
            // 所有自定义 Advisor
            TokenBudgetAdvisor tokenBudgetAdvisor,
            AuditTraceAdvisor auditTraceAdvisor,
            RateLimitAdvisor rateLimitAdvisor,
            OutputGuardrailAdvisor outputGuardrailAdvisor,
            AuthAdvisor authAdvisor,
            InputSanitizeAdvisor inputSanitizeAdvisor,
            PrefetchRagAdvisor prefetchRagAdvisor,
            ToolCallingAdvisor toolCallingAdvisor) {
        
        return builder
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(
                tokenBudgetAdvisor,
                auditTraceAdvisor,
                rateLimitAdvisor,
                outputGuardrailAdvisor,
                authAdvisor,
                inputSanitizeAdvisor,
                new MessageChatMemoryAdvisor(redisChatMemory),
                prefetchRagAdvisor,
                toolCallingAdvisor
            )
            .build();
    }
}
```

### 11.2.1 @Tool 注解工具调用

Spring AI 2.0 中，`@Tool` 注解是定义 Agent 工具的核心方式。`ToolCallingAdvisor` 会自动扫描并编排工具调用循环。

```java
package com.enterprise.kb.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 企业知识库 Agent 工具集 —— 对接内部 OA/ERP 系统
 */
@Component
public class EnterpriseTools {

    private final ErpService erpService;
    private final OaService oaService;
    
    public EnterpriseTools(ErpService erpService, OaService oaService) {
        this.erpService = erpService;
        this.oaService = oaService;
    }
    
    /**
     * 查询员工信息 —— 读操作，自动执行
     */
    @Tool(description = "根据员工姓名或工号查询员工基本信息，包括部门、职位、入职日期")
    public EmployeeInfo queryEmployee(
            @ToolParam(description = "员工姓名或工号") String keyword) {
        return erpService.findEmployee(keyword);
    }
    
    /**
     * 查询部门人员列表 —— 读操作，自动执行
     */
    @Tool(description = "查询指定部门的所有在职员工列表")
    public List<EmployeeInfo> listDepartmentMembers(
            @ToolParam(description = "部门名称") String department) {
        return erpService.findByDepartment(department);
    }
    
    /**
     * 提交请假申请 —— 写操作，需要 Human-in-the-Loop 审批
     */
    @Tool(description = "提交员工请假申请。⚠️ 此操作需要用户二次确认后才能执行")
    public String submitLeaveRequest(
            @ToolParam(description = "员工工号") String employeeId,
            @ToolParam(description = "请假开始日期 (yyyy-MM-dd)") String startDate,
            @ToolParam(description = "请假结束日期 (yyyy-MM-dd)") String endDate,
            @ToolParam(description = "请假类型: 年假/事假/病假") String leaveType,
            ToolContext context) {
        
        // Human-in-the-Loop: 写操作需要审批确认
        if (!context.isApproved()) {
            context.requestApproval("确认要为员工 " + employeeId + 
                " 提交 " + leaveType + " 请假申请吗？(" + startDate + " 至 " + endDate + ")");
            return "⏳ 等待用户确认...";
        }
        return oaService.submitLeave(employeeId, startDate, endDate, leaveType);
    }
    
    /**
     * 查询知识库统计信息 —— 读操作
     */
    @Tool(description = "查询企业知识库的统计信息，包括文档总数、Chunk总数、最近更新日期")
    public KnowledgeStats queryKnowledgeStats() {
        return erpService.getKnowledgeStats();
    }
}
```

### 11.2.2 SmartRoutingChatModel 多模型智能路由

```java
package com.enterprise.kb.ai.chat;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业级多模型智能路由器
 * 
 * 核心策略：
 * - 简单查询（无工具调用、短文本）→ 经济模型 (deepseek-v4-flash)
 * - 中等复杂度（含工具调用、中等长度）→ 标准模型 (deepseek-v4-flash)
 * - 高复杂度（多工具编排、长文本推理）→ 旗舰模型 (deepseek-v4-pro, 低温度)
 * - 主模型故障时自动 Fallback 到备用模型（含熔断器保护）
 */
@Component
public class SmartRoutingChatModel implements ChatModel {
    
    private final Map<ModelTier, List<ChatModel>> modelPool;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    
    public enum ModelTier {
        ECONOMY,    // 经济型: deepseek-v4-flash
        STANDARD,   // 标准型: deepseek-v4-flash
        PREMIUM,    // 旗舰型: deepseek-v4-pro (低 temperature)
        LOCAL       // 本地型: 预留
    }
    
    public SmartRoutingChatModel(Map<ModelTier, List<ChatModel>> modelPool) {
        this.modelPool = modelPool;
        this.circuitBreakers = new HashMap<>();
        modelPool.values().stream().flatMap(List::stream)
            .forEach(m -> circuitBreakers.put(m.toString(), new CircuitBreaker(5, 30_000)));
    }
    
    @Override
    public ChatResponse call(Prompt prompt) {
        // 1. 分析查询复杂度
        ModelTier tier = analyzeComplexity(prompt);
        
        // 2. 获取该层级的健康模型
        List<ChatModel> candidates = modelPool.get(tier).stream()
            .filter(m -> circuitBreakers.get(m.toString()).isHealthy())
            .toList();
        
        // 3. 该层全部不健康 → 降级到下一层
        if (candidates.isEmpty()) {
            tier = fallbackTier(tier);
            candidates = modelPool.get(tier);
        }
        
        // 4. 轮询选择 + 熔断保护
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int idx = Math.abs(roundRobinIndex.getAndIncrement() % candidates.size());
            ChatModel model = candidates.get(idx);
            
            try {
                ChatResponse response = model.call(prompt);
                circuitBreakers.get(model.toString()).recordSuccess();
                return response;
            } catch (Exception e) {
                circuitBreakers.get(model.toString()).recordFailure();
                if (attempt == candidates.size() - 1) throw e;
            }
        }
        throw new IllegalStateException("所有模型不可用");
    }
    
    private ModelTier analyzeComplexity(Prompt prompt) {
        String text = prompt.getInstructions().stream()
            .map(Object::toString).reduce("", String::concat);
        boolean hasTools = text.contains("toolCall") || text.contains("function");
        int estimatedTokens = text.length() / 4;
        
        if (hasTools && estimatedTokens > 4000) return ModelTier.PREMIUM;
        if (hasTools || estimatedTokens > 2000) return ModelTier.STANDARD;
        return ModelTier.ECONOMY;
    }
    
    private ModelTier fallbackTier(ModelTier tier) {
        return switch (tier) {
            case PREMIUM -> ModelTier.STANDARD;
            case STANDARD -> ModelTier.ECONOMY;
            case ECONOMY -> ModelTier.LOCAL;
            case LOCAL -> throw new IllegalStateException("无可用模型");
        };
    }
    
    /**
     * 简易熔断器：连续失败 5 次熔断 30 秒
     */
    static class CircuitBreaker {
        private final int failureThreshold;
        private final long recoveryMs;
        private int failureCount = 0;
        private long lastFailureTime = 0;
        private boolean open = false;
        
        CircuitBreaker(int threshold, long recoveryMs) {
            this.failureThreshold = threshold;
            this.recoveryMs = recoveryMs;
        }
        
        boolean isHealthy() {
            if (open && System.currentTimeMillis() - lastFailureTime > recoveryMs) {
                open = false; failureCount = 0; // 半开恢复
            }
            return !open;
        }
        
        void recordSuccess() { failureCount = 0; }
        
        void recordFailure() {
            failureCount++;
            if (failureCount >= failureThreshold) {
                open = true;
                lastFailureTime = System.currentTimeMillis();
            }
        }
    }
}
```

### 11.2.3 MCP Client 配置（企业外部工具集成）

Spring AI 2.0 原生支持 MCP SDK 2.0 协议，通过 YAML 配置即可对接外部 MCP Server：

```yaml
# application.yml - MCP Client 配置 (Spring AI 2.0.0 GA)
spring:
  ai:
    mcp:
      client:
        sse:
          connections:
            # 内部 ERP 系统 MCP Server
            erp-server:
              url: http://erp-mcp.internal:8080/sse
            # 内部 OA 系统 MCP Server  
            oa-server:
              url: http://oa-mcp.internal:8080/sse
        stdio:
          connections:
            # 本地 Stdio MCP Server
            local-tools:
              command: java
              args: ["-jar", "/opt/mcp/local-tools-server.jar"]
```

```java
// MCP Client Bean 配置（可选，YAML 配置已足够自动装配）
@Configuration
public class McpClientConfig {
    
    @Bean
    public ToolCallingAdvisor toolCallingAdvisor(
            ToolRegistry toolRegistry,
            McpToolRegistry mcpToolRegistry) {
        
        // 合并 @Tool 注解注册的工具和 MCP 协议引入的工具
        ToolRegistry merged = ToolRegistry.merge(toolRegistry, mcpToolRegistry);
        
        return ToolCallingAdvisor.builder()
            .toolRegistry(merged)
            .maxToolCalls(10)           // 最大工具调用次数，防止无限循环
            .toolCallApprovalRequired(true) // 写操作需要人类审批
            .build();
    }
}
```

### 11.2.4 结构化输出 `.entity()` 调用示例

Spring AI 2.0 的结构化输出能力将 LLM 响应自动映射为 Java Record/Pojo：

```java
// 1. 定义结构化输出模型
public record AnswerWithCitations(
    String answer,                        // 回答正文
    List<Citation> citations,             // 引用列表
    ConfidenceLevel confidence            // 置信度
) {}

public record Citation(
    int refNumber,       // [ref-N] 编号
    String sourceFile,   // 来源文件
    Integer pageNumber,  // 页码
    String excerpt       // 引用原文片段
) {}

public enum ConfidenceLevel { HIGH, MEDIUM, LOW, UNCERTAIN }

// 2. 调用方式
@RestController
public class StructuredOutputController {
    
    private final ChatClient chatClient;
    
    @PostMapping("/api/v1/agent/chat/structured")
    public AnswerWithCitations chatStructured(@RequestBody ChatRequest request) {
        return chatClient.prompt()
            .user(request.query())
            .advisors(/* ... */)
            .call()
            .entity(AnswerWithCitations.class);  // ★ 自动 JSON Schema → POJO 映射
    }
    
    // 列表结构化输出
    @PostMapping("/api/v1/agent/chat/extract-faqs")
    public List<FaqItem> extractFaqs(@RequestBody String documentText) {
        return chatClient.prompt()
            .user("从以下文档提取常见问答对：\n" + documentText)
            .call()
            .entity(new ParameterizedTypeReference<List<FaqItem>>() {}); // 列表映射
    }
}

public record FaqItem(String question, String answer, String category) {}
```

**自校正机制**：当 JSON 解析失败时，Spring AI 2.0 的 `StructuredOutputValidationAdvisor` 会自动将错误反馈给模型并要求重新生成，默认最多重试 3 次。业务代码应始终对输出做 `@Valid` 校验：

```java
public record AnswerWithCitations(
    @NotBlank String answer,
    @NotEmpty List<@Valid Citation> citations,
    @NotNull ConfidenceLevel confidence
) {}
```

### 11.2.5 StructuredOutputValidationAdvisor 自校正配置

```java
package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 结构化输出自校正 Advisor 配置
 * 
 * 当 LLM 输出的 JSON 无法映射为 Java 对象时：
 * 1. StructuredOutputValidationAdvisor 捕获解析异常
 * 2. 将错误详情（字段名/预期类型/实际值）反馈给模型
 * 3. 要求模型修正后重新生成
 * 4. 默认最多重试 3 次，超出后抛出异常
 */
@Configuration
public class StructuredOutputConfig {

    @Bean
    public StructuredOutputValidationAdvisor validationAdvisor() {
        return StructuredOutputValidationAdvisor.builder()
            .outputType(AnswerWithCitations.class)   // ★ 必须：指定输出类型，自动生成 JSON Schema
            .maxRepeatAttempts(3)                    // 最大重复尝试次数（默认3），注意方法名是 repeat 不是 retry
            .build();
    }
}
```

### 11.2.6 ToolSearchToolCallingAdvisor 按需工具检索

当 Agent 注册了数十甚至上百个工具时，将所有工具的 JSON Schema 全部注入 Context Window 会导致 Token 严重浪费甚至溢出。Spring AI 2.0 的 `ToolSearchToolCallingAdvisor` 在每次 LLM 调用前，根据用户查询动态搜索和选择最相关的 Top-N 工具，只将相关工具的定义注入。

```java
package com.enterprise.kb.ai.config;

import org.springframework.ai.chat.client.advisor.ToolSearchToolCallingAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按需工具检索 Advisor
 * 
 * 工作流程：
 * 1. 启动时将所有 @Tool 工具的定义向量化存入专门的 Tool VectorStore
 * 2. 每次用户请求到达时，用用户 query 检索最相关的 Top-K 个工具
 * 3. 只将匹配的工具 Schema 注入 LLM 上下文
 * 4. 大幅减少无关工具的 Token 消耗（实测可节省 60-80% 工具定义 Token）
 */
@Configuration
public class ToolSearchConfig {

    @Bean
    public ToolSearchToolCallingAdvisor toolSearchAdvisor(
            ToolIndex toolIndex) {
        
        return ToolSearchToolCallingAdvisor.builder()
            .toolIndex(toolIndex)              // ToolIndex（如 LuceneToolIndex）用于工具搜索
            .maxResults(5)                     // 每次请求最多检索 5 个最相关工具
            .build();
    }

    /**
     * 创建 Lucene 工具索引：
     * 1. 启动时扫描所有 @Tool 方法
     * 2. 为每个工具的 name + description 建立 Lucene 索引
     * 3. 运行时按用户 query 检索最相关的 Top-K 工具
     */
    @Bean
    public ToolIndex toolIndex(ToolRegistry toolRegistry) {
        return LuceneToolIndex.builder()
            .toolRegistry(toolRegistry)
            .build();
    }
}
```

**应用方式**：在企业级 ChatClient 装配中，用 `ToolSearchToolCallingAdvisor` 替代普通的 `ToolCallingAdvisor`，即可实现透明升级：

```java
@Configuration
public class AgentChatClientConfig {
    
    @Bean
    public ChatClient knowledgeAgentChatClient(
            ChatClient.Builder builder,
            SmartRoutingChatModel routingChatModel,
            ChatMemory redisChatMemory,
            // ... 其他 Advisor
            PrefetchRagAdvisor prefetchRagAdvisor,
            ToolSearchToolCallingAdvisor toolSearchAdvisor,  // ★ 替换 ToolCallingAdvisor
            StructuredOutputValidationAdvisor validationAdvisor) {
        
        return builder
            .defaultSystem("你是企业知识库 RAG Agent 助手。")
            .defaultAdvisors(
                // ... 限流/鉴权/脱敏/记忆/RAG Advisor
                toolSearchAdvisor,       // 先进：按需工具检索
                validationAdvisor         // 结构化输出自校正
            )
            .build();
    }
}
```

### 11.3 SSE 流式事件推送

```java
package com.enterprise.kb.api.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agent")
public class AgentStreamController {
    
    private final ChatClient knowledgeAgent;
    
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamChat(
            @RequestParam String sessionId,
            @RequestParam String query,
            @RequestHeader("Authorization") String token) {
        
        return knowledgeAgent.prompt()
            .user(query)
            .advisors(spec -> spec
                .param("chat_memory_conversation_id", sessionId)
                .param("authorization", token))
            .stream()
            .chatResponse()
            .flatMap(response -> {
                // 处理流式文本块
                if (response.getResult() != null) {
                    String text = response.getResult().getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        return Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                            .event("TOKEN")
                            .data(new TokenEvent(text))
                            .build());
                    }
                }
                // 处理元数据（RAG 溯源）
                if (response.getMetadata() != null) {
                    List<RetrievalResult> trace = 
                        (List<RetrievalResult>) response.getMetadata().get("rag_trace");
                    if (trace != null) {
                        return Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                            .event("TRACE")
                            .data(new TraceEvent(trace))
                            .build());
                    }
                }
                return Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                    .event("HEARTBEAT")
                    .data(new HeartbeatEvent())
                    .build());
            })
            .concatWith(Mono.just(ServerSentEvent.<AgentStreamEvent>builder()
                .event("DONE")
                .data(new DoneEvent(sessionId))
                .build()));
    }
}

// SSE 事件类型定义
sealed interface AgentStreamEvent permits TokenEvent, TraceEvent, ToolCallEvent, ErrorEvent, HeartbeatEvent, DoneEvent {}
record TokenEvent(String token) implements AgentStreamEvent {}
record TraceEvent(List<RetrievalResult> sources) implements AgentStreamEvent {}
record ToolCallEvent(String toolName, String status, String result) implements AgentStreamEvent {}
record ErrorEvent(String code, String message) implements AgentStreamEvent {}
record HeartbeatEvent() implements AgentStreamEvent {}
record DoneEvent(String sessionId) implements AgentStreamEvent {}
```

---

## 第十二章：安全护栏体系

### 12.1 InputSanitizeAdvisor

```java
package com.enterprise.kb.ai.advisor;

import java.util.regex.Pattern;

/**
 * 输入安全护栏：PII 脱敏 + Prompt 注入检测
 * 
 * 继承 BaseAdvisor，仅重写 before() 做请求预处理
 */
@Component
public class InputSanitizeAdvisor extends BaseAdvisor {
    
    // PII 正则模式
    private static final Pattern PHONE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.-]+@[\\w.-]+\\.[a-z]{2,}");
    
    // Prompt 注入检测关键词
    private static final List<String> INJECTION_PATTERNS = List.of(
        "ignore previous", "ignore all", "forget everything",
        "system prompt", "you are now", "new instructions",
        "忽略之前的", "忘记所有", "新的指令", "你的系统提示词"
    );
    
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userText = request.userText();
        
        // 1. Prompt 注入检测
        if (detectInjection(userText)) {
            throw new SecurityException("检测到 Prompt 注入攻击，请求已被拦截");
        }
        
        // 2. PII 脱敏
        String sanitized = userText;
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("1***-****-****");
        sanitized = ID_CARD_PATTERN.matcher(sanitized).replaceAll("******************");
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("***@***.***");
        
        return ChatClientRequest.from(request)
            .userText(sanitized)
            .build();
    }
    
    private boolean detectInjection(String text) {
        String lower = text.toLowerCase();
        return INJECTION_PATTERNS.stream().anyMatch(lower::contains);
    }
    
    @Override
    public int getOrder() { return 300; }
}
```

### 12.2 OutputGuardrailAdvisor

```java
/**
 * 输出安全护栏：敏感词过滤 + 合规审查 + 幻觉拦截
 * 
 * 继承 BaseAdvisor，仅重写 after() 做响应后处理
 */
@Component
public class OutputGuardrailAdvisor extends BaseAdvisor {
    
    private static final String SAFE_RESPONSE = "抱歉，由于合规要求，无法提供该信息。";
    
    // 敏感词/竞品黑名单（生产环境从配置中心动态加载）
    private final Set<String> blacklist = Set.of("competitor_x", "competitor_y");
    
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String output = response.response().getResult().getOutput().getText();
        
        // 后置审查
        if (containsBlacklistedTerms(output)) {
            return ChatClientResponse.from(response)
                .response(new ChatResponse(List.of(
                    new Generation(new AssistantMessage(SAFE_RESPONSE))
                )))
                .build();
        }
        
        return response;
    }
    
    private boolean containsBlacklistedTerms(String text) {
        return blacklist.stream().anyMatch(text::contains);
    }
    
    @Override
    public int getOrder() { return 110; }
}
```

### 12.3 TokenBudgetAdvisor

```java
/**
 * Token 预算控制 Advisor —— 成本追踪 + 超额拦截
 * 继承 BaseAdvisor，before() 检查预算，after() 记录消耗
 */
@Component
public class TokenBudgetAdvisor extends BaseAdvisor {
    
    private final MeterRegistry meterRegistry;
    private final Counter tokenCounter;
    private final RedissonClient redissonClient;
    
    private static final long DAILY_BUDGET = 1_000_000; // 日预算 100万 Token
    private static final long SINGLE_REQUEST_BUDGET = 50_000; // 单次 5万
    
    public TokenBudgetAdvisor(MeterRegistry registry, RedissonClient redisson) {
        this.meterRegistry = registry;
        this.redissonClient = redisson;
        this.tokenCounter = Counter.builder("rag.token.total")
            .description("AI Token 总消耗").register(registry);
    }
    
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String tenantId = (String) request.context().get("tenant_id");
        String todayKey = "token:budget:" + tenantId + ":" + LocalDate.now();
        
        long dailyUsed = redissonClient.getAtomicLong(todayKey).get();
        if (dailyUsed >= DAILY_BUDGET) {
            throw new TokenBudgetExceededException(
                "日 Token 预算已耗尽 (已用: " + dailyUsed + " / 上限: " + DAILY_BUDGET + ")");
        }
        return request;
    }
    
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String tenantId = (String) response.response().getMetadata().get("tenant_id");
        Long tokensUsed = extractTokenUsage(response);
        if (tokensUsed != null) {
            String todayKey = "token:budget:" + tenantId + ":" + LocalDate.now();
            redissonClient.getAtomicLong(todayKey).addAndGet(tokensUsed);
            tokenCounter.increment(tokensUsed);
        }
        return response;
    }
    
    private Long extractTokenUsage(ChatClientResponse response) {
        return response.response().getMetadata().getUsage().getTotalTokens();
    }
    
    @Override
    public int getOrder() { return 10; }
}

/**
 * Token 预算耗尽异常（建议拆分到独立文件 TokenBudgetExceededException.java）
 */
class TokenBudgetExceededException extends RuntimeException {
    public TokenBudgetExceededException(String message) { super(message); }
}
```

---

## 第十三章：可观测性体系

### 13.1 Spring AI 原生观测配置

```yaml
# application.yml
spring:
  ai:
    observations:
      enabled: true
      include-prompt: false      # 生产环境关闭（避免敏感信息泄露）
      include-response: false
      include-completion: true

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: kb-rag-agent
  tracing:
    sampling:
      probability: 1.0          # 全量采样（可调低）
  otlp:
    endpoint: http://otel-collector:4317
```

### 13.2 AuditTraceAdvisor

```java
/**
 * 全链路审计 Advisor —— 继承 BaseAdvisor，before()记录开始时间，after()持久化审计日志
 */
@Component
public class AuditTraceAdvisor extends BaseAdvisor {
    
    private final KbAuditLogRepository auditLogRepository;
    private final MeterRegistry meterRegistry;
    
    // 使用 ThreadLocal 在 before/after 之间传递计时起点
    private final ThreadLocal<Long> startTimeHolder = new ThreadLocal<>();
    private final ThreadLocal<String> traceIdHolder = new ThreadLocal<>();
    
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        startTimeHolder.set(System.currentTimeMillis());
        traceIdHolder.set(Span.current().getSpanContext().getTraceId());
        return request;
    }
    
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        long latency = System.currentTimeMillis() - startTimeHolder.get();
        String traceId = traceIdHolder.get();
        
        // 异步落库审计日志
        saveAuditLog(traceId, response, latency);
        
        // 记录指标
        Timer.builder("rag.request.latency")
            .tag("outcome", "success")
            .register(meterRegistry)
            .record(latency, TimeUnit.MILLISECONDS);
        
        startTimeHolder.remove();
        traceIdHolder.remove();
        return response;
    }
    
    @Async("etlExecutor")
    protected void saveAuditLog(String traceId, ChatClientResponse response, long latency) {
        KbAuditLogEntity log = KbAuditLogEntity.builder()
            .traceId(traceId)
            .finalAnswer(response.response().getResult().getOutput().getText())
            .latencyMs((int) latency)
            .build();
        auditLogRepository.save(log);
    }
    
    @Override
    public int getOrder() { return 50; }
}
```

### 13.3 AiBusinessMetrics

```java
/**
 * AI 业务指标采集
 */
@Component
public class AiBusinessMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // 检索指标
    private final Timer retrievalLatency;
    private final Counter retrievalTotal;
    private final Counter retrievalCacheHit;
    
    // LLM 指标
    private final Counter llmCallTotal;
    private final Counter llmTokenTotal;
    private final Counter llmErrorTotal;
    
    // 业务指标
    private final Counter feedbackLike;
    private final Counter feedbackDislike;
    private final Counter toolCallSuccess;
    
    public AiBusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        
        // 检索指标
        this.retrievalLatency = Timer.builder("rag.retrieval.latency")
            .description("混合检索延迟（毫秒）")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
        this.retrievalTotal = Counter.builder("rag.retrieval.total")
            .description("检索总次数")
            .register(meterRegistry);
        this.retrievalCacheHit = Counter.builder("rag.retrieval.cache.hit")
            .description("语义缓存命中次数")
            .register(meterRegistry);
        
        // LLM 指标
        this.llmCallTotal = Counter.builder("rag.llm.call.total")
            .register(meterRegistry);
        this.llmTokenTotal = Counter.builder("rag.llm.token.total")
            .register(meterRegistry);
        this.llmErrorTotal = Counter.builder("rag.llm.error.total")
            .register(meterRegistry);
        
        // 业务指标
        this.feedbackLike = Counter.builder("rag.feedback.like")
            .register(meterRegistry);
        this.feedbackDislike = Counter.builder("rag.feedback.dislike")
            .register(meterRegistry);
        this.toolCallSuccess = Counter.builder("rag.tool.call.success")
            .register(meterRegistry);
    }
    
    // 便捷记录方法
    public void recordRetrieval(long latencyMs) {
        retrievalLatency.record(latencyMs, TimeUnit.MILLISECONDS);
        retrievalTotal.increment();
    }
    
    public void recordFeedback(boolean isPositive) {
        if (isPositive) feedbackLike.increment();
        else feedbackDislike.increment();
    }
    
    // 指标导出端点
    @ReadOperation
    public Map<String, Object> getSummary() {
        return Map.of(
            "total_llm_calls", llmCallTotal.count(),
            "total_tokens", llmTokenTotal.count(),
            "error_rate", llmErrorTotal.count() / Math.max(1, llmCallTotal.count()),
            "feedback_positive_rate", feedbackLike.count() / 
                Math.max(1, feedbackLike.count() + feedbackDislike.count()),
            "cache_hit_rate", retrievalCacheHit.count() / Math.max(1, retrievalTotal.count())
        );
    }
}
```

### 13.4 Grafana 大盘设计

| Dashboard | 面板内容 | 刷新频率 |
|-----------|---------|---------|
| **概览大盘** | QPS、P95延迟、错误率、Token消耗趋势、活跃用户数 | 30s |
| **检索大盘** | 检索延迟分位数、向量/BM25各自延迟、缓存命中率、召回Chunk数分布 | 30s |
| **LLM 大盘** | Token消耗(按模型/租户)、模型调用次数、Fallback次数、首Token延迟 | 30s |
| **业务大盘** | 用户反馈(点赞率趋势)、Bad Case数量、工具调用成功率、知识库入库量 | 1min |

---

## 第十四章：知识库运维

### 14.1 Chunk 运维 API

```java
@RestController
@RequestMapping("/api/v1/admin/chunks")
public class ChunkAdminController {
    
    private final KbChunkRepository chunkRepository;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    
    /**
     * 编辑 Chunk 文本 → 异步重新向量化 → 更新 Milvus
     */
    @PutMapping("/{chunkId}")
    public ApiResponse<ChunkVO> updateChunk(@PathVariable String chunkId, 
                                             @RequestBody ChunkUpdateRequest request) {
        KbChunkEntity chunk = chunkRepository.findById(chunkId)
            .orElseThrow(() -> new ResourceNotFoundException("Chunk not found"));
        
        // 备份原始内容
        chunk.setOriginalContent(chunk.getContent());
        chunk.setContent(request.getContent());
        chunkRepository.save(chunk);
        
        // 异步重新向量化并更新 Milvus
        asyncUpdateVector(chunk);
        
        return ApiResponse.success(ChunkVO.from(chunk));
    }
    
    @Async("etlExecutor")
    private void asyncUpdateVector(KbChunkEntity chunk) {
        // 1. 删除旧向量
        if (chunk.getVectorId() != null) {
            vectorStore.delete(List.of(chunk.getVectorId()));
        }
        // 2. 重新向量化
        float[] newEmbedding = embeddingModel.embed(chunk.getContent());
        // 3. 写入新向量
        Document vectorDoc = new Document(chunk.getContent(), Map.of(
            "chunk_id", chunk.getId(),
            "doc_id", chunk.getDocId(),
            "chunk_type", chunk.getChunkType()
        ));
        String newVectorId = vectorStore.add(List.of(vectorDoc)).get(0);
        // 4. 更新 PG 中的 vector_id
        chunk.setVectorId(newVectorId);
        chunkRepository.save(chunk);
    }
    
    /**
     * 索引全量重建
     */
    @PostMapping("/rebuild-index")
    public ApiResponse<RebuildProgress> rebuildIndex(@RequestParam(defaultValue = "false") boolean full) {
        String taskId = UUID.randomUUID().toString();
        // 异步启动重建任务
        rebuildService.startRebuild(taskId, full);
        return ApiResponse.success(new RebuildProgress(taskId, "STARTED"));
    }
}
```

### 14.2 PromptTemplateManager

```java
@Service
public class PromptTemplateManager {
    
    private final KbPromptTemplateRepository templateRepository;
    private final RedisTemplate<String, String> redisTemplate;
    
    private static final String CACHE_KEY_PREFIX = "prompt:template:";
    
    /**
     * 获取 Prompt 模板（Redis 缓存 → DB 回源）
     */
    public PromptTemplate getTemplate(String name, String version) {
        String cacheKey = CACHE_KEY_PREFIX + name + ":" + version;
        
        // 1. 尝试 Redis 缓存
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JsonUtils.fromJson(cached, PromptTemplate.class);
        }
        
        // 2. DB 回源
        KbPromptTemplateEntity entity = templateRepository
            .findByNameAndVersionAndStatus(name, version, "ACTIVE")
            .orElseThrow(() -> new ResourceNotFoundException("Prompt template not found"));
        
        PromptTemplate template = new PromptTemplate(entity.getTemplateText());
        
        // 3. 写入缓存（TTL 1小时）
        redisTemplate.opsForValue().set(cacheKey, JsonUtils.toJson(template), 
            Duration.ofHours(1));
        
        return template;
    }
    
    /**
     * A/B 测试：根据分组返回不同版本
     */
    public PromptTemplate getTemplateForABTest(String name, String abGroup) {
        KbPromptTemplateEntity entity = templateRepository
            .findByNameAndAbTestGroupAndStatus(name, abGroup, "AB_TESTING")
            .orElseGet(() -> templateRepository
                .findByNameAndStatusOrderByVersionDesc(name, "ACTIVE")
                .orElseThrow());
        return new PromptTemplate(entity.getTemplateText());
    }
}
```

---

# 第六卷：工程质量保障（质量层）

## 第十五章：测试策略

### 15.1 测试金字塔

```
          ┌───────────┐
          │  E2E 测试 │  ← 关键业务流程（Playwright/Cypress）
          │  5-10个   │
          ├───────────┤
          │ AI 评估   │  ← Golden Dataset 自动化评估（CI 集成）
          │  200+ 用例│
          ├───────────┤
          │ 集成测试  │  ← Testcontainers (PG+Milvus+ES+Redis)
          │  50-80个  │
          ├───────────┤
          │ 单元测试  │  ← JUnit5 + Mockito, 覆盖率 > 80%
          │  200+ 个  │
          └───────────┘
```

### 15.2 Testcontainers 集成测试

```java
@SpringBootTest
@Testcontainers
class HybridRetrievalServiceIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:18");
    
    @Container
    static MilvusContainer milvus = new MilvusContainer("milvusdb/milvus:v2.6");
    
    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer("elasticsearch:8.19.0");
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.0")
        .withExposedPorts(6379);
    
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.ai.vectorstore.milvus.host", milvus::getHost);
        registry.add("spring.elasticsearch.uris", 
            () -> "http://" + es.getHost() + ":" + es.getMappedPort(9200));
        registry.add("spring.data.redis.host", redis::getHost);
    }
    
    @Autowired
    private HybridRetrievalService retrievalService;
    
    @Test
    void shouldReturnBetterResultsWithHybridSearch() {
        // Given: 准备测试数据
        seedTestDocuments();
        
        // When: 执行混合检索
        List<RetrievalResult> results = retrievalService.hybridSearch(
            "如何配置 Spring Boot 数据源？", 5, null, "test-tenant");
        
        // Then: 验证结果
        assertThat(results).hasSizeGreaterThanOrEqualTo(1);
        assertThat(results.get(0).getFusionScore()).isGreaterThan(0);
        // 混合检索应包含向量和 BM25 两种得分
        assertThat(results).anyMatch(r -> r.getVectorScore() != null);
        assertThat(results).anyMatch(r -> r.getBm25Score() != null);
    }
}
```

### 15.3 Mock LLM 测试策略

```java
@TestConfiguration
public class MockLlmConfig {
    @Bean
    @Primary
    public ChatModel mockChatModel() {
        return prompt -> {
            // 返回预设响应，避免调用真实 API
            String userMessage = prompt.getInstructions().get(0).getText();
            String mockAnswer = generateMockAnswer(userMessage);
            return new ChatResponse(List.of(
                new Generation(new AssistantMessage(mockAnswer))
            ));
        };
    }
}
```

---

## 第十六章：AI 评估体系

### 16.1 Golden Dataset 构建

```java
/**
 * Golden Dataset —— 用于自动化评估的标准问答对
 */
public record GoldenQAPair(
    String id,
    String question,           // 测试问题
    String expectedKeywords,   // 期望包含的关键词（宽松匹配）
    String expectedAnswer,     // 理想回答（严格匹配，LLM-as-Judge 用）
    List<String> expectedChunkIds, // 期望命中的 Chunk ID
    String category            // 分类：事实查询/推理/表格/多文档
) {
    public static List<GoldenQAPair> loadFromJson(String path) {
        // 从 JSON 文件加载（可版本化管理在 Git 中）
    }
}
```

### 16.2 评估指标

| 指标 | 定义 | 计算方式 | 目标 |
|------|------|---------|------|
| **Context Relevance** | 检索结果与问题的相关性 | LLM-as-Judge 评分 (1-5) | > 4.0 |
| **Faithfulness** | 回答是否忠于检索上下文（无幻觉） | LLM-as-Judge 评分 (1-5) | > 4.5 |
| **Answer Correctness** | 回答的正确性 | 关键词匹配 + LLM-as-Judge | > 85% |
| **Top-K Recall** | Top-K 中是否包含正确答案所需的 Chunk | 期望 Chunk 命中率 | > 90% |
| **MRR** | 首个相关 Chunk 的倒数排名均值 | Mean Reciprocal Rank | > 0.8 |

### 16.3 CI/CD 自动化评估

```java
/**
 * 在 CI 中运行的自动评估执行器
 */
@Component
public class EvalRunner {
    
    private final ChatClient chatClient;
    private final List<GoldenQAPair> goldenDataset;
    
    @EventListener(ApplicationReadyEvent.class)
    public void runEvalIfCiProfile() {
        if ("ci".equals(System.getProperty("spring.profiles.active"))) {
            EvalReport report = runFullEval();
            if (report.overallScore() < 0.7) {
                throw new EvalFailedException("评估得分低于阈值: " + report.overallScore());
            }
        }
    }
    
    private EvalReport runFullEval() {
        List<EvalResult> results = goldenDataset.stream()
            .map(qa -> {
                String answer = chatClient.prompt().user(qa.question()).call().content();
                double contextRelevance = evaluateContextRelevance(answer, qa);
                double faithfulness = evaluateFaithfulness(answer, qa);
                return new EvalResult(qa, answer, contextRelevance, faithfulness);
            })
            .toList();
        
        return new EvalReport(results);
    }
}
```

---

## 第十七章：部署与运维

### 17.1 Kubernetes 生产部署（关键配置）

```yaml
# kb-api-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kb-api
spec:
  replicas: 3
  selector:
    matchLabels:
      app: kb-api
  template:
    metadata:
      labels:
        app: kb-api
    spec:
      containers:
      - name: kb-api
        image: registry.example.com/kb-rag-agent:latest
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        env:
        - name: JAVA_OPTS
          value: "-Xmx3g -XX:+UseZGC -XX:+ZGenerational"
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: kb-api-hpa
spec:
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### 17.2 Milvus 分布式集群架构

```
                    ┌──────────────┐
                    │  SDK / gRPC  │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   Proxy      │ (负载均衡 + 请求路由)
                    └──────┬───────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
    ┌──────▼──────┐ ┌──────▼──────┐ ┌──────▼──────┐
    │  Query Node │ │  Data Node  │ │  Index Node │
    │  (向量检索) │ │  (数据写入) │ │  (索引构建) │
    └──────┬──────┘ └──────┬──────┘ └──────┬──────┘
           │               │               │
    ┌──────▼───────────────▼───────────────▼───────┐
    │              Shared Storage                  │
    │  ┌──────────┐  ┌───────────┐  ┌───────────┐  │
    │  │  etcd    │  │  MinIO    │  │  Pulsar   │  │
    │  │ (元数据) │  │ (向量存储)│  │ (消息队列)│  │
    │  └──────────┘  └───────────┘  └───────────┘  │
    └──────────────────────────────────────────────┘
```

---

## 第十八章：交付验收标准

### 18.1 功能完整性指标（13 项）

| # | 验收项 | 目标值 | 测试方法 |
|---|-------|--------|---------|
| 1 | 文档格式支持 | PDF(电子/扫描件)、Docx、MD、TXT、HTML | 各格式上传 10 份样本测试 |
| 2 | 表格解析完整性 | 表格结构破损率 < 5%，表格跨 Chunk 断裂率 < 5% | 含表格 PDF 样本测试 |
| 3 | 扫描件 OCR 可用率 | > 85% | 扫描件样本集测试 |
| 4 | 混合检索 Top-5 命中率 | > 85% | Golden Dataset 评估 |
| 5 | 专有名词/标题词命中率 | > 90% | 专有名词专项测试集 |
| 6 | 回答溯源覆盖率 | 100%（每次回答均携带引用来源） | 自动化巡检 |
| 7 | 多轮对话上下文连贯性 | > 90%（10 轮内） | 多轮对话测试集 |
| 8 | 多租户数据隔离 | 0 跨租户数据泄露 | 安全渗透测试 |
| 9 | 安全护栏拦截率（注入） | > 95% | 注入样本测试集 |
| 10 | PII 脱敏准确率 | > 99% | PII 样本测试集 |
| 11 | 审计日志完整性 | 100%（每次问答均有 Trace） | 全量日志检查 |
| 12 | Chunk 编辑后检索一致性 | 编辑后 10s 内检索结果反映最新内容 | 端到端测试 |
| 13 | 用户反馈闭环可用 | 点赞/点踩 → kb_feedback 落库 → Bad Case 可查询 | 功能验收 |

### 18.2 性能指标

| 指标 | 目标值 | 测试条件 |
|------|--------|---------|
| 流式首 Token 延迟 (TTFT) | < 1.5s | 常规查询，排除网络延迟 |
| 问答 P95 延迟 | < 3s | 100 QPS 并发 |
| 单次检索延迟（10万 Chunk） | < 200ms | Milvus + ES 并行 |
| 单文档（100页）解析入库 | < 2min | @Async ETL 管道 |
| 10万 Chunk 索引重建 | < 30min | 全量重建任务 |
| 语义缓存命中率 | > 30% | 运行一周后统计 |
| 系统可用性 | > 99.9% | 生产环境月度统计 |

### 18.3 工程质量指标

| 指标 | 目标值 |
|------|--------|
| 单元测试覆盖率 | > 80% |
| 集成测试覆盖核心链路 | 100% |
| OpenTelemetry Trace 覆盖率 | 100%（核心链路） |
| Prompt 外部化配置率 | 100% |
| API 文档覆盖率 | 100% |
| 数据库迁移脚本版本化管理 | 100%（Flyway/Liquibase） |
| CI/CD 流水线自动部署 | 是 |

---

# 附录

## 附录 A：Spring AI 2.0 完整依赖清单

```xml
<!-- Maven BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Spring AI 核心 -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-client-chat</artifactId>
    </dependency>
    
    <!-- 模型提供商 -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-deepseek</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-openai</artifactId>
    </dependency>
    
    <!-- 向量存储 -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-vector-store-milvus</artifactId>
    </dependency>
    
    <!-- ETL -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-document-tika</artifactId>
    </dependency>
    
    <!-- MCP -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-client</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server</artifactId>
    </dependency>
    
    <!-- Advisor -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-advisors-vector-store</artifactId>
    </dependency>
</dependencies>
```

## 附录 B：核心 application.yml 配置模板

```yaml
spring:
  application:
    name: kb-rag-agent
  
  # 数据源
  datasource:
    url: jdbc:postgresql://localhost:5432/kb_rag_agent
    username: ${DB_USERNAME:kbadmin}
    password: ${DB_PASSWORD:kbpass123}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
  
  # AI 配置 (Spring AI 2.0)
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
    vectorstore:
      milvus:
        host: localhost
        port: 19530
        database-name: kb_rag_agent
        collection-name: kb_chunks
        embedding-dimension: 1024
        index-type: HNSW
        metric-type: COSINE
    observations:
      enabled: true
      include-prompt: false
      include-response: false
  
  # Elasticsearch
  elasticsearch:
    uris: http://localhost:9200
    connection-timeout: 5s
    socket-timeout: 30s
  
  # Redis
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3s
  
  # MinIO
  minio:
    endpoint: http://localhost:9000
    access-key: minioadmin
    secret-key: minioadmin
    bucket: kb-documents

  # 虚拟线程
  threads:
    virtual:
      enabled: true
```

## 附录 C：API 接口清单

| 模块 | 方法 | 路径 | 说明 | 阶段 |
|------|------|------|------|------|
| **文档管理** | POST | `/api/v1/documents/upload` | 上传文档 | P0 |
| | GET | `/api/v1/documents` | 文档列表 | P0 |
| | GET | `/api/v1/documents/{id}` | 文档详情 | P0 |
| | DELETE | `/api/v1/documents/{id}` | 删除文档 | P2 |
| | GET | `/api/v1/documents/{id}/chunks` | 文档 Chunk 列表 | P1 |
| | GET | `/api/v1/documents/{id}/progress` | 解析进度 | P1 |
| **Agent 对话** | POST | `/api/v1/agent/chat` | 同步对话 | P0 |
| | GET | `/api/v1/agent/chat/stream` | SSE 流式对话 | P0 |
| | GET | `/api/v1/agent/sessions` | 会话列表 | P1 |
| | GET | `/api/v1/agent/sessions/{id}/messages` | 历史消息 | P1 |
| | DELETE | `/api/v1/agent/sessions/{id}` | 删除会话 | P2 |
| **检索调试** | POST | `/api/v1/retrieval/search` | 检索测试（返回详细得分） | P1 |
| | POST | `/api/v1/retrieval/compare` | 对比检索（向量 vs 混合） | P2 |
| **运维管理** | PUT | `/api/v1/admin/chunks/{id}` | 编辑 Chunk | P2 |
| | DELETE | `/api/v1/admin/chunks/{id}` | 软删除 Chunk | P2 |
| | POST | `/api/v1/admin/rebuild-index` | 索引重建 | P2 |
| | GET | `/api/v1/admin/audit-logs` | 审计日志查询 | P2 |
| | GET | `/api/v1/admin/metrics` | AI 业务指标 | P2 |
| **Prompt 管理**| GET | `/api/v1/admin/prompts` | Prompt 模板列表 | P2 |
| | POST | `/api/v1/admin/prompts` | 创建模板 | P2 |
| | PUT | `/api/v1/admin/prompts/{id}` | 更新模板 | P2 |
| | POST | `/api/v1/admin/prompts/{id}/activate` | 激活版本 | P2 |
| **反馈** | POST | `/api/v1/feedback` | 提交反馈 | P2 |
| | GET | `/api/v1/feedback/stats` | 反馈统计 | P2 |
| **健康检查** | GET | `/actuator/health` | 健康检查 | P0 |
| | GET | `/actuator/metrics` | 指标端点 | P2 |

## 附录 D：避坑指南与反模式

| # | 反模式 | 问题 | 正确做法 |
|---|--------|------|---------|
| 1 | 在 Controller 中直接 `new ChatClient()` | 无法复用 Advisor 链，配置散落 | 通过 `@Configuration` Bean 统一组装 |
| 2 | 在业务代码中拼接 Prompt | 难以维护、无法做 A/B 测试 | 使用 `PromptTemplateManager` 外部化管理 |
| 3 | 硬编码 API Key | 安全风险 | 使用 K8s Secrets / Vault / 环境变量 |
| 4 | 同步阻塞调用 OCR/LLM | Web 线程耗尽 | 使用 `@Async` + 虚拟线程异步处理 |
| 5 | 在 `synchronized` 块中调用虚拟线程 I/O | 虚拟线程 Pinning 退化 | 确保底层客户端无 synchronized 阻塞 |
| 6 | 不限次数的工具调用循环 | Token 消耗失控 | 设置 `ToolCallingAdvisor` 最大调用次数 |
| 7 | 检索结果全量注入 Prompt | Context Window 溢出 | 计算 Token 数，超限时压缩或减少 Top-K |
| 8 | Advisor 顺序随意编排 | 审计不完整、限流被绕过 | 严格按 Order 设计排列 |
| 9 | 不做检索结果评估 | Prompt 变更导致召回率下降 | CI 中集成 Golden Dataset 自动评估 |
| 10 | Milvus 连接不调优 | 高并发下连接耗尽 | 配置连接池参数，设置合理的超时时间 |
| 11 | SSE 代理无超时配置 | 长连接被网关切断 | 配置心跳保活 + `proxy_read_timeout` 延长 |
| 12 | 向量库替代所有检索 | 专有名词命中率低 | 混合检索（向量 + BM25）是必经之路 |
| 13 | 信任模型输出直接执行写操作 | 模型幻觉导致数据污染 | 写操作通过 `ToolContext.requestApproval()` 要求人工确认 |
| 14 | 流式响应中工具调用未处理异常 | 前端 UI 卡死或状态混乱 | SSE 推送专用 ERROR 事件 + 前端 `onError` 优雅降级 |
| 15 | 结构化输出不做 `@Valid` 校验 | 字段缺失/类型错误未被发现 | 对 `.entity()` 输出施加 Bean Validation 约束 |
| 16 | 工具定义 Schema 膨胀导致上下文溢出 | Agent 携带大量工具时 Context Window 不够 | 使用 `ToolSearchToolCallingAdvisor` 按需检索工具，只注入相关 Schema |
| 17 | 向量库选型不考虑数据规模 | 千万级以内用专用库造成运维负担 | 千万级 Chunk 以下优先 PGVector，降低架构复杂度；上亿级选 Milvus |
| 18 | 测试环境调用真实 LLM | 测试不稳定、成本高、速度慢 | 单元测试和 CI 中使用 `@Primary` Mock ChatModel，E2E 才调用真实 API |
| 19 | 上下文压缩机制缺失 | 长文档场景检索后直接注入全量内容导致溢出 | 使用轻量模型或 `TokenTextSplitter` 先压缩证据再注入 Prompt |
| 20 | 多模型路由缺少熔断保护 | 故障模型反复重试拖垮整体服务 | 在 `SmartRoutingChatModel` 中集成熔断器，连续失败后自动隔离降级 |

---

> **报告结语**
>
> 本报告基于 Spring AI 2.0.0 GA 的最新特性与最佳实践，结合 2026 年企业级知识库 RAG Agent 市场的真实需求，提供了一套完整的、分阶段、可操作的全景落地实现方案。
>
> 按照五阶段路线图（24 周）稳步推进，你将交付一个：
> - **答得准**：混合检索 Top-5 命中率 > 85%
> - **有依据**：100% 回答携带来源溯源
> - **可运维**：Chunk 级编辑、全链路 Trace、Grafana 监控
> - **够安全**：多租户隔离、PII 脱敏、Prompt 注入防护
> - **能进化**：GraphRAG、Multi-Agent、反馈闭环、A/B 测试
>
> 的**企业级 AI 知识中枢**。
>
> **技术栈检查清单**：Java 21 ✓ | Spring Boot 4.1 ✓ | Spring AI 2.0.0 GA ✓ | Milvus ✓ | Elasticsearch ✓ | PostgreSQL 18 ✓ | Redis 8 ✓ | OpenTelemetry ✓ | Vue3 ✓
>
> ---
>
> 🤖 Generated with [Claude Code](https://claude.com/claude-code)
