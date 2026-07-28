# 企业知识库 RAG Agent 工作台 & 知识库助手

基于 **Spring AI 2.0** 的企业级 RAG 平台。支持多格式文档智能解析、混合检索（向量 + BM25 + RRF）、带来源溯源的 Agent 对话、全链路可观测。

## 技术栈

| 层 | 技术 |
|---|------|
| 语言 | Java 21（虚拟线程） |
| 框架 | Spring Boot 4.1 + Spring AI 2.0.0 GA |
| 向量库 | Milvus 2.6 / pgvector |
| 全文检索 | Elasticsearch 8.19（BM25） |
| 数据库 | PostgreSQL 18 |
| 缓存 | Redis 8 |
| 对象存储 | MinIO |
| 可观测 | OpenTelemetry + Micrometer + Prometheus + Grafana |
| 前端 | Vue3 + TypeScript |

## 快速开始

```bash
# 1. 启动基础设施
docker compose -f docker/docker-compose.yml up -d

# 2. 编译项目
mvn clean compile

# 3. 启动应用
mvn spring-boot:run -pl kb-api
```

## 项目结构

```
├── kb-commons/        # 通用工具、DTO、异常
├── kb-domain/         # JPA Entity、Repository
├── kb-infrastructure/ # Milvus/ES/Redis/MinIO 适配
├── kb-etl/            # 文档 ETL 管道
├── kb-ai-core/        # ChatClient、Advisor、混合检索
├── kb-api/            # REST + SSE 接口（启动入口）
├── kb-admin/          # 运维后台
├── kb-eval/           # AI 评估
├── docker/            # Docker Compose
└── docs/              # 设计文档 + 进度追踪
```

## 文档

- [全景实现报告](docs/project-implement/企业知识库%20RAG%20Agent%20工作台：Spring%20AI%202.0%20全景实现报告.md) — 完整架构设计、技术方案、五阶段路线图
- [进度追踪](docs/project-progress/项目阶段推进任务清单完成记录.md) — 当前推进状态

## 核心特性

- **双链路文档解析**：Tika 原生 + OCR 智能路由（扫描件/复杂表格自动识别）
- **保护式切分**：HTML 表格/图片块完整性保护
- **混合检索**：向量语义 + BM25 关键词 + RRF 融合 + 重排序
- **Agent 对话**：Prefetch 预检索 → 证据注入 → 带 [ref-N] 溯源引用的流式回答
- **企业级安全**：多租户隔离、RBAC 权限、PII 脱敏、Prompt 注入检测
- **全链路可观测**：OpenTelemetry Trace + Prometheus Metrics + Grafana 大盘

## License

MIT
