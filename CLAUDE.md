# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台，目标能力：多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1（基础设施与 MVP 验证）已全部完成；Phase 2（知识引擎攻坚：解析路由、保护式切分、模块化混合检索、溯源 SSE）尚未开始。设计唯一依据见 `docs/project-implement/README.md`（v2 拆分修订版，2026-07-31），进度追踪见 `docs/project-progress/项目阶段推进任务清单完成记录.md`。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API)
- 向量库: pgvector (PG 扩展) / Milvus 2.6（`kb.vector-store.provider` 配置切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 8.19 + Redis 8 + MinIO
- 认证: OAuth2 Resource Server (JWT) · 接入 Casdoor（前端 PKCE 流程）
- 前端: Vue3 + TypeScript + Element Plus + Pinia + Vite 6
- Maven 多模块（8 个子模块）

> **版本说明**：文档中的基础设施版本（PG 18、ES 8.19、Milvus 2.6、Redis 8）指 ECS 服务器上的**服务端部署版本**；pom 中对应的是**客户端库版本**，二者独立管理（如 elasticsearch-java 客户端为 8.14.3），不属于不一致。

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse、BusinessException 体系、Constants（含 RRF_K、DEFAULT_TOP_K）
├── kb-domain/         # 8 JPA Entity + 8 Repository + 6 枚举 + schema.sql（8 业务表 + kb_embeddings）
├── kb-infrastructure/ # vectorstore/（pgvector+Milvus 双后端条件装配）、MinIO；ES/Redis 客户端 Bean 尚未建立
├── kb-etl/            # 文档 ETL：MinIO 拉取 → Tika 解析 → TokenTextSplitter → PG 落库 → VectorStore 向量化（@Async 虚拟线程）
├── kb-ai-core/        # ChatClient 配置（QuestionAnswerAdvisor 基础 RAG）、ChatService（同步 + Flux 流式）
├── kb-api/            # REST Controller + SSE + SecurityConfig + JwtUtils + GlobalExceptionHandler（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（空模块，待开发）
├── kb-eval/           # AI 评估（空模块，待开发）
├── frontend/          # Vue3 前端（Vite 6；Login/Chat 两个视图，SSE 流式对话 + 文档上传）
└── docs/              # 设计文档（project-implement/ 按章拆分，入口 README.md）+ 进度追踪
```

模块依赖：kb-commons ← kb-domain ← kb-infrastructure ← kb-etl / kb-ai-core ← kb-api；kb-admin、kb-eval 依赖 kb-ai-core。

## 运行环境

- **基础设施托管于 ECS**：PG / Milvus / ES / Redis / MinIO 均部署在远程 ECS 服务器，**本地无需搭建**，后端通过启动环境变量注入连接信息。
- **API 端口 8090**：服务器 8080 已被其他服务占用，本项目启动变量配置 `SERVER_PORT=8090`；前端 `frontend/.env` 的 `BACKEND_URL=http://localhost:8090` 与之配套（Vite dev server 代理 `/api`，前端端口 5173）。
- 常用环境变量：`SERVER_PORT`、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`KB_VECTOR_STORE_PROVIDER`（pgvector|milvus）及 `KB_MILVUS_*` / `KB_PGVECTOR_*`、`MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET`、`ES_URIS`、`REDIS_HOST`、`JWT_ISSUER_URI`、`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`（默认值见 `application-infra.yml` / `application-ai.yml`）。
- 启动：后端 `mvn spring-boot:run -pl kb-api`；前端 `cd frontend && npm install && npm run dev`。

## 当前实现要点（Phase 1）

- 对话：`ChatConfig` 组装 ChatClient + `QuestionAnswerAdvisor`（topK=5，similarityThreshold=0.5）；`AgentController` 提供 `POST /api/v1/chat`（同步）与 `/chat/stream`（SseEmitter，事件 `{"token":...}` / `[DONE]`）
- 上传：`DocumentService`（类型白名单 PDF/DOCX/MD/TXT/HTML → MinIO → kb_document 落库 → 触发异步 ETL）；`DocumentEtlService`（Tika → TokenTextSplitter 800/200 → kb_chunk 落库 → `VectorStore.add()` 批量向量化，chunkId=vectorId）
- 认证：`SecurityConfig`（/actuator/health|info permitAll，/api/** authenticated，其余 denyAll，无状态会话）；`JwtUtils` 映射 Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁用 Spring AI 原生 auto-config，`VectorStoreConfig` 按 `@ConditionalOnProperty(kb.vector-store.provider)` 条件创建 PgVectorStore / MilvusVectorStore
- 配置拆分：`application.yml`（kb-api）经 `spring.config.import` 导入 `application-infra.yml`（kb-infrastructure）+ `application-ai.yml`（kb-ai-core）
- 当前**无任何测试类**；kb-admin / kb-eval 为空模块

## 注意事项

- Maven 4 reactor 自动解析父子关系，子模块使用 `<parent/>` 即可，无需显式声明父坐标
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 7.x 要求）
- 父 POM dependencyManagement 已预埋后续阶段依赖：elasticsearch-java 8.14.3、jsoup 1.18.1、redisson 4.6.1、testcontainers 1.20.1
- pgvector 模式需先以 superuser 执行 `CREATE EXTENSION IF NOT EXISTS vector;`（服务器 PG 若已启用可跳过）
- Phase 2 检索架构为 Spring AI 2.0 模块化 RAG（`RetrievalAugmentationAdvisor` + 自研 `HybridDocumentRetriever`/`RrfFusion` + ES ik BM25 双路 + gte-rerank）；Milvus 原生混合检索经源码级核验后否决。决策全文见 `docs/project-implement/10-混合检索引擎.md` §10.0
