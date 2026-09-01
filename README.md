# 企业知识库 RAG Agent 工作台

基于 Spring AI 2.0 的企业级 RAG 平台。区别于 Dify/RAGFlow/MaxKB 等通用/平台方案，本项目聚焦 Spring AI 生态内的**深度可溯源（多路得分透明的检索调试台）+ 企业权限集成（Casdoor/租户隔离/权限感知检索）+ 运维闭环（评估/审计/护栏/可观测）**，适合对审计与溯源有强需求的 Java 技术栈企业。

**当前阶段**：✅ Phase 1-3、优化冲刺六簇、安全加固专项六簇均收官；✅ **Phase 4 全阶段收官**（2026-08-22：七簇——观测地基 / 面板统计 / Chunk 运维与重建 / Bad Case 运营闭环 / MCP Server 产品化 / 生产加固与压测 / 文档与格式收尾；用户侧验收回传通过：新格式 E2E + 交付文档评审）。交付文档三件套见 `docs/delivery/`。**Phase 5（项目最后阶段）六簇推进中**（2026-08-27）：① 收尾清零 ✅（B5 CRITICAL 清零 + 观察项销账）② 评估进化机器侧批1-4 就绪（批5 用户侧合并复跑待回传）③ 语义缓存 ✅ 收官（用户侧 E2E 通过，已合入 main）④ GraphRAG ✅ 收官（2026-08-27：五批落地 + 用户侧 E2E 回传通过，多跳准确率门禁双轮持线 ≥80%；分支合并回 main 随批5 窗口）⑤ Agent 编排 / ⑥ 产品化收尾待启动——见 07 卷。用户侧运维回传项唯一源 = `用户侧待执行项清单`。

## 技术栈

| 层 | 技术 |
|---|------|
| 语言 | Java 21（虚拟线程，`--enable-preview`） |
| 框架 | Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4 |
| LLM | 主答双形态：GLM-5.3-Flash（缺省）/ DeepSeek V4（回落），`rag.routing.primary.provider` 一切即切；备用 qwen3.8-flash（主备熔断路由 + 双供应商 SLA 指标；路由/改写轻任务同载体） |
| Embedding | 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API) |
| Rerank / Judge | `qwen3-rerank`（百炼 DashScope 端点）/ `qwen3.8-flash`（评估裁判 + 注入二判 + 图抽取） |
| 向量库 | pgvector / Milvus 2.6（`KB_VECTOR_STORE_PROVIDER` 切换，默认 milvus） |
| 数据库 | PostgreSQL 18 + Elasticsearch 9.4.2 + Neo4j 5.26.29 + Redis 8 + MinIO |
| 认证 | OAuth2 JWT（Casdoor，前端 PKCE 流程） |
| 前端 | Vue3 + TypeScript + Element Plus + Pinia + Vite 6 |
| 压测 | Gatling 3.15 Java DSL（kb-loadtest 独立模块，显式触发） |

> 基础设施版本指 ECS 服务器上的服务端部署版本；pom 中的客户端库版本独立管理。

## 核心能力

- **双链路架构**：`ragAgentChatClient`（纯检索零工具）+ `toolAgentChatClient`（纯工具零检索），请求体 `mode: rag|tool` 显式分流；意图路由（闲聊旁路直答 / 知识问答走全链）
- **混合检索**：向量 + BM25[+Graph] 多路并行召回 → RRF 融合 → qwen3-rerank 精排（故障降级截断）；多轮追问消解改写；编号化 [ref-N] 溯源锚定 + 空证据拒答；**间接注入扫描**（召回证据入生成前同源词表逐条扫描，warn/exclude 双策略）
- **GraphRAG（Phase 5 簇④）**：实体/关系抽取（ETL 终态帧异步派发、旁路不阻断入库、幂等重写 + 令牌桶双档分桶）→ 图路检索**零 LLM**（查询嵌入 → 实体向量匹配 → 1 跳展开 → chunk 反查）入多路 RRF；`rag.graph.enabled` 缺省关、关闭态零回归；多跳问答专项集 30 例 + 准确率门禁（≥80%）
- **语义缓存（Phase 5 簇③）**：`CacheCheckAdvisor` 路由后门控前——命中短路重放 + 溯源同形 / 未命中流末五闸异步写入；Redis 8 内建搜索引擎经 Redisson RSearch 零新增依赖（租户域隔离 + KNN 余弦 0.95 + 文档失效反查），事件驱动失效；`rag.cache.enabled` 缺省关
- **带溯源的 Agent 对话**：SSE 无名 TOKEN/ERROR/DONE + 命名 TRACE（三路溯源、chunk 级「查看原文」）+ TOOL_CALL（HITL 审批卡片）
- **MCP Server**：Streamable HTTP `/mcp` 三工具（search / get_document / ask），JWT 身份守卫 + scope 治理 + 独立限流桶与轻量审计
- **工具链与 HITL**：企业 Mock 工具（对齐真实 OA/ERP 契约），读工具自动执行、写工具三段式人工审批（Redis 账本 fail-closed，TTL + 一次性消费 + 租户绑定）
- **护栏与配额**：输入消毒（NFKC 归一化 + PII 七类识别器注册表掩码 + 注入拦截）→ L2 语义判定（可疑触发备用模型二判，fail-open 回落）→ 输出黑名单整段替换（流式聚合后验）→ 租户日 Token 预算 + Redisson 令牌桶限流；**词表热重载**（DB 单轨 + 前端运维台管理 + FLAG 观察生命周期）
- **租户隔离 fail-closed 两层**：入口身份守卫 + 检索器有上下文无租户返回空结果（多路零触达）
- **全链路审计与运营闭环**：AuditTraceAdvisor 异步旁路落库三态；👍/👎 反馈经 trace_id 回填；Bad Case 闭环（查询 → 根因四分类标注 → Golden Set 回灌 Git Ops 通道 → CI 复跑）
- **多轮记忆**：Redis ChatMemory（窗口 20）+ PG 异步归档 + 历史会话续聊回填
- **多格式解析支线**：支持 PDF / DOCX / PPTX / XLSX / MD / TXT / HTML；SmartParsingRouter 三路由（NATIVE Tika / DEEP DocMind / OCR 兜底）+ 表格 HTML 保护切分 + Contextual 语境增强 + chunk 确定性 ID；增量重入库（reparse/replace + 版本列 + 蓝绿 diff 清理）
- **Chunk 运维与重建**：Chunk CRUD（编辑 → 异步重嵌入 / 软删 / 恢复）+ 全量重建（PG 事实源重解析 + ES 孤儿清扫，租户域任务表）
- **评估体系（kb-eval）**：探针组（auto/vector/hybrid/chain）+ Golden Dataset 267 条（干净 110 + 注入 127 + 多跳 30，三分区门禁契约 + 多跳准确率门禁）+ 间接注入评估 + CI 门禁（干净集零命中 + 防域拦截率分区阈值 + 分类单维不崩地板）
- **Prompt Git Ops**：全部提示词模板收编专类（对话链 / 解析链 / 评估链各一），git log = 版本历史，外部化率 100%
- **全链路可观测**：17+ 项 `rag.*` 业务指标 + Micrometer Observation → OTel → OTLP Langfuse（LLM trace 树）+ Prometheus 告警 14 条 + Grafana 五面板（含双供应商 SLA）+ 全链路审计
- **生产加固**：Flyway 迁移版本化 + 容器化（compose healthcheck/自动重启/AppCDS）+ 灾备最小集（PG 备份 + 恢复演练 + 99.5% 兜底自检）+ Gatling 压测四场景（检索真压 / 生成桩压 / 真实 LLM 采样 / SSE 并发会话）

## 项目结构

```
├── kb-commons/        # ApiResponse、BusinessException、PII 识别器注册表等共享组件
├── kb-domain/         # JPA Entity + Repository + 枚举 + schema.sql + Flyway 迁移
├── kb-infrastructure/ # 双向量库条件装配、MinIO、ES、文档解析客户端（DocMind/OCR）
├── kb-etl/            # ETL：解析路由 → 保护性切分 → 消毒 → PG → 向量化 → ES 双写；增量重入库
├── kb-ai-core/        # 纯 RAG 核心：多路检索（向量+BM25[+图谱]）+RRF+重排、Advisor 链（审计/护栏/配额/路由/门控/缓存）、记忆、指标
├── kb-ai-agent/       # Agent 事务域：Mock 工具 + HITL 审批账本 + MCP 三件套（search/get_document/ask）
├── kb-api/            # REST + SSE + MCP 端点 + SecurityConfig + JWT（启动入口，端口 8090）
├── kb-admin/          # 运维后台（Chunk 运维与重建 + Bad Case 闭环 + 护栏词表管理）
├── kb-eval/           # 评估：EvalRunner + 探针 + Golden Dataset 267 + CI 门禁
├── kb-loadtest/       # Gatling 压测四场景 + 生成桩（显式触发）
├── infra/             # 部署资产（docker-compose 应用/监控双栈 + .env 模板 + 备份脚本 + 告警规则）
├── frontend/          # Vue3 前端（Login + Chat 溯源对话 + Documents + Debug 检索调试台 + Chunks 观测台 + Admin 运维中心五 Tab）
└── docs/              # 设计文档（project-implement/）+ 进度追踪（project-progress/）+ 交付文档（delivery/）+ 复盘优化（project-optimization/）
```

## 运行环境

PG / Milvus / ES / Redis / MinIO 统一部署在 ECS 服务器，**本地无需搭建**，启动时通过环境变量指向服务器即可。本地 8080 端口被占用，API 服务固定运行在 **8090**（前端 `frontend/.env` BACKEND_URL 配套，Vite 代理 `/api`）。

生产部署采用容器化形态：根 Dockerfile + `infra/docker-compose.app.yml`（healthcheck / 自动重启 / 日志轮转 / AppCDS 训练服务）+ `infra/docker-compose.monitoring.yml`（Prometheus/Grafana/Jaeger/node-exporter）。详见 `docs/delivery/运维手册`。

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
# 观测：TRACING_OTLP_ENABLED=true + LANGFUSE_OTLP_AUTH=Basic <base64(pk:sk)>（缺省零导出零噪音）
# GraphRAG（簇④，缺省关）：RAG_GRAPH_ENABLED=true + NEO4J_URI / NEO4J_USERNAME / NEO4J_PASSWORD

# 启动后端（端口 8090；spring-boot:run fork JVM 必须带 --enable-preview，与 surefire 同理）
mvn spring-boot:run -pl kb-api -Dspring-boot.run.jvmArguments="--enable-preview"

# 启动前端（5173）
cd frontend && npm install && npm run dev
```

## 文档

- [全景实现报告 v2（按章拆分，设计唯一依据）](docs/project-implement/README.md)——v2 + 历次修订注记（随各章头部版本递增，现推进至 v2.8x 系）
- [交付文档三件套](docs/delivery/README.md)——运维手册 / API 文档 / 用户使用手册（簇⑦ 交付，用户评审通过）
- [进度追踪（按任务行定位）](docs/project-progress/项目阶段推进任务清单完成记录.md)
- [用户侧待执行项清单](docs/project-progress/用户侧待执行项清单.md)——机器侧就绪、待用户执行事项的唯一源
- [Phase 1-3 复盘与优化方案](docs/project-optimization/)（六簇优化冲刺全收官）
- [Phase 4 复审与规划方案（调研实证版）](docs/project-optimization/Phase%204%20复审与规划方案（调研实证版）.md)——七簇推进计划与定案记录
- [Phase 5 复审与规划方案（调研实证版）](docs/project-optimization/Phase%205%20复审与规划方案（调研实证版）.md)——六簇推进基线（收尾清零 → 评估进化 → 语义缓存 → GraphRAG → Agent 编排 → 产品化收尾）

## 阶段概览

| 阶段 | 范围 | 状态 |
|---|---|---|
| Phase 1 | MVP 闭环：上传 → 解析 → 切分 → 向量化 → RAG 对话 + Casdoor 认证 | ✅ |
| Phase 2 | 模块化混合检索（双路+RRF+重排）、来源溯源、解析路由升级（DocMind/OCR）、多轮记忆 | ✅ |
| Phase 3 | 护栏配额、租户隔离、审计、反馈闭环、意图路由、双链路拆分（rag/tool）、评估门禁 | ✅（17 项） |
| 优化冲刺 | 六簇：流式计账/熔断加固、检索调优 A/B、语境增强、Bad Case 治理、护栏加固、增量重入库 | ✅ |
| 安全加固专项 | 六簇：词表工程与输出面扩充、平台层零缺口、PII 注册表化、间接注入闭环、L2 语义判定、词表运营与对抗自动化 | ✅ |
| Phase 4 | 七簇：观测地基 / 面板统计 / Chunk 运维与索引重建 / Bad Case 运营闭环 / MCP Server 产品化 / 生产加固压测 / 文档与格式收尾 | ✅ 全阶段收官（2026-08-22，用户侧验收通过：新格式 E2E + 文档评审） |
| Phase 5 | 六簇（复审定案）：收尾清零 / 评估进化 / 语义缓存 / GraphRAG / Agent 编排 / 产品化收尾 | 🔄 推进中——① 收尾清零 ✅ / ② 评估进化机器侧就绪（批5 待回传）/ ③ 语义缓存 ✅（已合入 main）/ ④ GraphRAG ✅（2026-08-27 收官）/ ⑤⑥ 待启动 |
