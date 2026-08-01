# 企业知识库 RAG Agent 工作台

基于 Spring AI 2.0 的企业级 RAG 平台。区别于 Dify/RAGFlow/MaxKB 等通用/平台方案，本项目聚焦 Spring AI 生态内的**深度可溯源（双路得分透明的检索调试台）+ 企业权限集成（Casdoor/RBAC/权限感知检索）+ 运维闭环（评估/审计/可观测）**，适合对审计与溯源有强需求的 Java 技术栈企业。

**当前阶段**：✅ Phase 1（MVP 闭环）已完成 —— 文档上传 → Tika 解析 → Token 切分 → pgvector/Milvus 向量化 → DeepSeek V4 RAG 对话（同步 + SSE 流式）全链路可用；Phase 2（模块化混合检索、来源溯源、解析路由升级）待启动，设计文档已于 2026-07-31 修订至 v2（检索架构重大调整，见文档索引）。

## 技术栈

| 层 | 技术 |
|---|------|
| 语言 | Java 21（虚拟线程，`--enable-preview`） |
| 框架 | Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4 |
| LLM | DeepSeek V4 (`deepseek-v4-flash`) |
| Embedding | 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API) |
| 向量库 | pgvector / Milvus 2.6（`KB_VECTOR_STORE_PROVIDER` 切换，默认 milvus） |
| 数据库 | PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO |
| 认证 | OAuth2 JWT（Casdoor，前端 PKCE 流程） |
| 前端 | Vue3 + TypeScript + Element Plus + Pinia + Vite 6 |

> 基础设施版本指 ECS 服务器上的服务端部署版本；pom 中的客户端库版本独立管理（如 elasticsearch-java 8.14.3）。

## 运行环境

PG / Milvus / ES / Redis / MinIO 统一部署在 ECS 服务器，**本地无需搭建**，启动时通过环境变量指向服务器即可。服务器 8080 端口已被占用，本项目 API 服务固定运行在 **8090**。

## 快速开始

```bash
# 编译
mvn clean compile

# 启动环境变量（最小集，默认值见 application-infra.yml / application-ai.yml）
export SERVER_PORT=8090
export DB_URL=jdbc:postgresql://<ecs-host>:5432/kb_rag_agent
export DB_USERNAME=<user>
export DB_PASSWORD=<password>
export MINIO_ENDPOINT=http://<ecs-host>:9000
export KB_MILVUS_HOST=<ecs-host>
export JWT_ISSUER_URI=https://<casdoor-host>
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
# 可选：KB_VECTOR_STORE_PROVIDER=pgvector（默认 milvus）、ES_URIS、REDIS_HOST、KB_PGVECTOR_* / KB_MILVUS_* 细项

# 启动后端（端口 8090）
mvn spring-boot:run -pl kb-api

# 启动前端（5173，/api 代理至 BACKEND_URL，.env 中默认 http://localhost:8090）
cd frontend && npm install && npm run dev
```

## 项目结构

```
├── kb-commons/        # 通用工具、DTO、异常
├── kb-domain/         # JPA Entity（8 表）、Repository、schema.sql（含 kb_embeddings）
├── kb-infrastructure/ # 向量库双后端条件装配、MinIO（ES/Redis 客户端 Bean 待建）
├── kb-etl/            # 文档 ETL 管道（@Async 虚拟线程）
├── kb-ai-core/        # ChatClient（QuestionAnswerAdvisor 基础 RAG）、ChatService
├── kb-api/            # REST + SSE + 安全认证（启动入口）
├── kb-admin/          # 运维后台（空模块，待开发）
├── kb-eval/           # AI 评估（空模块，待开发）
├── frontend/          # Vue3 前端（Login + Chat 两个视图）
└── docs/              # 设计文档 + 进度追踪
```

## 文档

- [全景实现报告 v2（按章拆分版）](docs/project-implement/README.md)（设计唯一依据）
- [进度追踪](docs/project-progress/项目阶段推进任务清单完成记录.md)（Phase 2 任务清单已与 v2 对齐）

## Phase 1 已实现

- 文档上传（MinIO，PDF/Docx/MD/TXT/HTML）→ Tika 解析 → Token 切分（800/200）→ kb_chunk 落库 → pgvector/Milvus 向量化
- DeepSeek V4 RAG 对话（QuestionAnswerAdvisor，topK=5）+ SSE 流式推送
- Casdoor OAuth2 JWT 认证（无状态会话，`owner` claim → tenantId）
- 统一 ApiResponse + 全局异常处理 + Logback 结构化日志（dev 彩色 / prod JSON）
- 双向量库后端切换（`kb.vector-store.provider`，Spring AI 原生 auto-config 已禁用）
- Vue3 前端：Casdoor PKCE 登录 + 流式对话 + 文档上传
