# 企业知识库 RAG Agent 工作台

基于 Spring AI 2.0 的企业级 RAG 平台。区别于 Dify/RAGFlow/MaxKB 等通用/平台方案，本项目聚焦 Spring AI 生态内的**深度可溯源（双路得分透明的检索调试台）+ 企业权限集成（Casdoor/租户隔离/权限感知检索）+ 运维闭环（评估/审计/护栏/可观测）**，适合对审计与溯源有强需求的 Java 技术栈企业。

**当前阶段**：✅ Phase 1-3 已收官（Golden 74 基线达标），优化冲刺六簇收官（流式计账/检索调优/语境增强/增量重入库等）；**Phase 4 已定案启动**（七簇推进：观测地基 → 面板统计 → Chunk 运维 → Bad Case 闭环 → MCP Server 产品化 → 生产加固压测 → 文档收尾），簇① 观测地基（Micrometer Observation → OTLP → Langfuse Cloud + Prometheus 告警规则）进行中。

## 技术栈

| 层 | 技术 |
|---|------|
| 语言 | Java 21（虚拟线程，`--enable-preview`） |
| 框架 | Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4 |
| LLM | DeepSeek V4 (`deepseek-v4-flash`)，备用 qwen3.7-plus（主备熔断路由） |
| Embedding | 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API) |
| Rerank / Judge | `qwen3-rerank`（百炼 MaaS 端点）/ `qwen3.7-plus`（评估裁判） |
| 向量库 | pgvector / Milvus 2.6（`KB_VECTOR_STORE_PROVIDER` 切换，默认 milvus） |
| 数据库 | PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO |
| 认证 | OAuth2 JWT（Casdoor，前端 PKCE 流程） |
| 前端 | Vue3 + TypeScript + Element Plus + Pinia + Vite 6 |

> 基础设施版本指 ECS 服务器上的服务端部署版本；pom 中的客户端库版本独立管理。

## 核心能力

- **双链路架构**：`ragAgentChatClient`（纯检索零工具）+ `toolAgentChatClient`（纯工具零检索），请求体 `mode: rag|tool` 显式分流；意图路由（闲聊旁路直答 / 知识问答走全链）
- **混合检索**：向量 + BM25 双路并行召回 → RRF 融合 → qwen3-rerank 精排（故障降级 fusion_score 截断）；多轮追问消解改写；编号化 [ref-N] 溯源锚定 + 空证据拒答
- **带溯源的 Agent 对话**：SSE 无名 TOKEN/ERROR/DONE + 命名 TRACE（三路溯源、chunk 级 docId「查看原文」）+ TOOL_CALL（HITL 审批卡片）
- **工具链与 HITL**：企业 Mock 工具（对齐真实 OA/ERP 契约），读工具自动执行、写工具三段式人工审批（Redis 账本 fail-closed，TTL + 一次性消费 + 租户绑定）
- **护栏与配额**：输入消毒（NFKC 归一化检测 + PII 掩码 + 注入拦截）→ 输出黑名单整段替换（流式聚合后验）→ 租户日 Token 预算 + Redisson 令牌桶限流（Redis 故障 fail-open）
- **租户隔离 fail-closed 两层**：入口身份守卫 + 检索器有上下文无租户返回空结果（双路零触达）
- **全链路审计**：AuditTraceAdvisor 最外层异步旁路落库，SUCCESS/REJECTED/ERROR 三态，查询脱敏、rewritten_query 捕获
- **用户反馈闭环**：👍/👎 可改评 + 期望回答，经 trace_id 回填审计日志，Bad Case 查询收敛
- **多轮记忆**：Redis ChatMemory（窗口 20）+ PG 异步归档 + 历史会话续聊回填
- **多格式解析支线**：SmartParsingRouter 三路由（NATIVE Tika / DEEP DocMind / OCR 兜底）+ 表格 HTML 保护切分 + Contextual 语境增强（默认开）+ chunk 确定性 ID
- **增量重入库**：reparse/replace + 版本列 + REINDEXING 占用 + 蓝绿 diff 三库清理；文档/Chunk 软删
- **评估体系（kb-eval）**：探针组（auto/vector/hybrid/chain）+ Golden Dataset 146 条（含注入样本）+ LLM-as-judge + CI 门禁
- **可观测（Phase 4 簇① 进行中）**：17+ 项 `rag.*` Micrometer 业务指标 + Micrometer Observation → OTel 桥接 → OTLP 导出 Langfuse Cloud（LLM trace 树）+ Prometheus 告警规则 11 条

## 项目结构

```
├── kb-commons/        # ApiResponse、BusinessException、TextSanitizer 消毒共享组件
├── kb-domain/         # 8 JPA Entity + Repository + 枚举 + schema.sql
├── kb-infrastructure/ # 双向量库条件装配、MinIO、ES、文档解析客户端（DocMind/OCR）
├── kb-etl/            # ETL：解析路由 → 保护性切分 → 消毒 → PG → 向量化 → ES 双写；增量重入库
├── kb-ai-core/        # 纯 RAG 核心：双路检索+RRF+重排、Advisor 链（审计/护栏/配额/路由/门控）、记忆、指标
├── kb-ai-agent/       # Agent 事务域：Mock 工具 + HITL 审批账本 + ToolCallingAdvisor
├── kb-api/            # REST + SSE + SecurityConfig + JWT（启动入口 KbRagAgentApplication，端口 8090）
├── kb-admin/          # 运维后台（Phase 4 簇③ 启用）
├── kb-eval/           # 评估：EvalRunner + 探针 + Golden Dataset + CI 门禁
├── infra/             # 基础设施配置（prometheus 告警规则等）
├── frontend/          # Vue3 前端（Login + Chat 溯源对话 + Documents + Debug 检索调试台 + Chunks 观测台）
└── docs/              # 设计文档（project-implement/）+ 进度追踪（project-progress/）+ 复盘优化（project-optimization/）
```

## 运行环境

PG / Milvus / ES / Redis / MinIO 统一部署在 ECS 服务器，**本地无需搭建**，启动时通过环境变量指向服务器即可。本地 8080 端口被占用，API 服务固定运行在 **8090**（前端 `frontend/.env` BACKEND_URL 配套，Vite 代理 `/api`）。

## 快速开始

```bash
# 编译（单模块构建须带 -am：兄弟模块不在本地仓库时单 -pl 解析失败）
mvn -q --no-transfer-progress clean install -DskipTests

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
# Phase 4 观测：TRACING_OTLP_ENABLED=true + LANGFUSE_OTLP_AUTH=Basic <base64(pk:sk)>（缺省零导出零噪音）

# 启动后端（端口 8090；spring-boot:run fork JVM 必须带 --enable-preview，与 surefire 同理）
mvn spring-boot:run -pl kb-api -Dspring-boot.run.jvmArguments="--enable-preview"

# 启动前端（5173）
cd frontend && npm install && npm run dev
```

## 文档

- [全景实现报告 v2（按章拆分，设计唯一依据）](docs/project-implement/README.md)——v2 + v2.1-v2.29 修正
- [进度追踪（按任务行定位）](docs/project-progress/项目阶段推进任务清单完成记录.md)
- [Phase 1-3 复盘与优化方案](docs/project-optimization/)（六簇优化冲刺全收官）
- [Phase 4 复审与规划方案（调研实证版）](docs/project-optimization/Phase%204%20复审与规划方案（调研实证版）.md)——七簇推进计划与定案记录

## 阶段概览

| 阶段 | 范围 | 状态 |
|---|---|---|
| Phase 1 | MVP 闭环：上传 → 解析 → 切分 → 向量化 → RAG 对话 + Casdoor 认证 | ✅ |
| Phase 2 | 模块化混合检索（双路+RRF+重排）、来源溯源、解析路由升级（DocMind/OCR）、多轮记忆 | ✅ |
| Phase 3 | 护栏配额、租户隔离、审计、反馈闭环、意图路由、双链路拆分（rag/tool）、评估门禁 | ✅（17 项） |
| 优化冲刺 | 六簇：流式计账/熔断加固、检索调优 A/B、语境增强、Bad Case 治理、护栏加固、增量重入库 | ✅ |
| Phase 4 | 七簇：观测地基 / 面板统计 / Chunk 运维与索引重建 / Bad Case 运营闭环 / MCP Server 产品化 / 生产加固压测 / 文档收尾 | 🚧 启动（簇① 进行中） |
| Phase 5 | 规划中（语义缓存、Multi-Agent、性能深度优化等，见路线图第八章） | ⏳ |
