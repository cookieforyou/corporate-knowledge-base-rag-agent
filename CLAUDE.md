# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台，支持多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

## 技术栈

- Java 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 GA
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 阿里云百炼 DashScope (`qwen3.7-text-embedding`)
- 向量库: pgvector (PG18 扩展) / Milvus 2.6（配置切换）
- PostgreSQL 18 + Elasticsearch 8.19 + Redis 8 + MinIO
- 认证: OAuth2 Resource Server (JWT) · 接入 Casdoor
- 前端: Vue3 + TypeScript + Element Plus + Pinia
- Maven 多模块（8 个子模块）

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse、BusinessException、Constants
├── kb-domain/         # 8 JPA Entity + 8 Repository + schema.sql
├── kb-infrastructure/ # vectorstore/ (pgvector+Milvus)、MinIO、ES、Redis
├── kb-etl/            # 文档 ETL：Tika 解析 → TokenTextSplitter → PG → VectorStore
├── kb-ai-core/        # ChatClient 配置、ChatService (手动RAG+检索日志)、BailianEmbeddingConfig
├── kb-api/            # REST Controller + SSE + SecurityConfig + JwtUtils（启动入口）
├── kb-admin/          # 运维后台（待开发）
├── kb-eval/           # AI 评估（待开发）
├── frontend/           # Vue3 前端（Vite）
└── docs/              # 设计文档 + 进度追踪
```

## 注意事项

- Maven 4 reactor 自动解析父子关系，子模块使用 `<parent/>` 即可，无需显式声明父坐标
- 配置文件按模块拆分：`application-infra.yml`、`application-ai.yml`，由 `application.yml` 通过 `spring.config.import` 导入
- VectorStore 双后端通过 `kb.vector-store.provider` 切换，Spring AI 原生 auto-config 已禁用 (`spring.ai.vectorstore.type=custom`)
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 7.x 要求）
- 启动前需以 superuser 执行 `CREATE EXTENSION IF NOT EXISTS vector;`
