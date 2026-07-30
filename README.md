# 企业知识库 RAG Agent 工作台

基于 Spring AI 2.0 的企业级 RAG 平台。

## 技术栈

| 层 | 技术 |
|---|------|
| 语言 | Java 21（虚拟线程） |
| 框架 | Spring Boot 4.1 + Spring AI 2.0.0 GA |
| LLM | DeepSeek V4 (`deepseek-v4-flash`) |
| Embedding | 阿里云百炼 DashScope (`qwen3.7-text-embedding`) |
| 向量库 | pgvector / Milvus 2.6（配置切换） |
| 数据库 | PostgreSQL 18 + Elasticsearch 8.19 + Redis 8 |
| 认证 | OAuth2 JWT（Casdoor） |
| 前端 | Vue3 + TypeScript + Element Plus |

## 快速开始

```bash
# 编译
mvn clean compile

# 环境变量（示例）
export DEEPSEEK_API_KEY=sk-xxx
export DASHSCOPE_API_KEY=sk-xxx
export JWT_ISSUER_URI=https://auth.example.com

# 启动后端
mvn spring-boot:run -pl kb-api

# 启动前端
cd frontend && npm install && npm run dev
```

## 项目结构

```
├── kb-commons/        # 通用工具、DTO、异常
├── kb-domain/         # JPA Entity、Repository
├── kb-infrastructure/ # 向量库双后端、ES、Redis、MinIO
├── kb-etl/            # 文档 ETL 管道
├── kb-ai-core/        # ChatClient、ChatService
├── kb-api/            # REST + SSE + 安全认证（启动入口）
├── kb-admin/          # 运维后台
├── kb-eval/           # AI 评估
├── frontend/           # Vue3 前端
└── docs/              # 设计文档
```

## 文档

- [全景实现报告](docs/project-implement/企业知识库%20RAG%20Agent%20工作台：Spring%20AI%202.0%20全景实现报告.md)
- [进度追踪](docs/project-progress/项目阶段推进任务清单完成记录.md)

## Phase 1 已实现

- 文档上传（MinIO）→ Tika 解析 → Token 切分 → pgvector/Milvus 向量化
- DeepSeek V4 RAG 对话 + SSE 流式推送 + 检索日志
- Casdoor OAuth2 JWT 认证
- Vue3 前端：登录 + 对话 + 文档上传
