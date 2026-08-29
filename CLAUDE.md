# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台：文档解析、混合检索（向量+BM25[+Graph] RRF 三路）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：此前完成：Phase 1-4、优化冲刺、安全加固专项。**Phase 5（项目最后阶段，六簇推进）**：推进基线 `docs/project-optimization/Phase 5 复审与规划方案（调研实证版）.md`；**簇①收官、簇②批5 用户侧复跑 ①-⑤ 全完成 + 三项处置决策定案（κ 定档 FAIL 观察带维持；L2 flash→plus 对照实验实证关闭 judge 能力假设——门禁逐位相同，根因定谳 = 越狱族样本×判据结构错位；决策 = L2 a→b 串行[路径 a 已落地：`EvalResult#l2RawVerdict` 五态原始裁决观测；路径 b 已实证收口：批5-b 复跑 0.672 净零增益（判据毛命中 1 例与超时 1 例对冲，措辞杠杆见顶）→ 批5-c 收官：复核修正根因 = 漏判样本多不达 L2 触发前置（生产链不达二判），19 例重建触发可达形态，**复跑 L2 联合门禁 0.918 ≥0.90 首达销账**（0.672→0.672→0.918；增益 = 重建 +17 / 除噪 +1 / 漂移净 −2；JAILBREAK 0.933 / MULTILINGUAL 0.903 压线），台账 19/20 C 裁决 + opaque-09 记 A，退出码残唯 MULTI_DOC 已知项；**e 契约随复跑结果并议定案**（2026-08-28 四项：SUSPECT 唯 BLOCK 计别 / 统一 0.90 待 G1 校准 / 连续 2 轮 ≥0.90 判据 / 残余二例批5-d 二轮重建）] / TABLE 条件销账（F=4.000 四轮持稳）/ κ 维持 flash 基线；G1 红队首跑用户侧并行；**批5-d 收官：连续 2 轮销账达成**（轮1 0.967 / 轮2 0.951 均 ≥0.90，稳定性判据首用成功；重建二靶点双轮 BLOCK，五轮轨迹 0.672→0.672→0.918→0.967→0.951，漏判面收敛于 judge 边界噪声）；κ 复校-② 定谳 = 判定面结构信息不对称；**并议五项裁决落地（16 章 v2.79）**：M1 全块核验视图（截断退役 + 上限）/ M2 理想回答物理隔离（双盲材直出、剥离层退役）/ M3 NRob 观察带（不计成败）/ M4 AC 随全维 / M5 缓议；**κ 复校-④「连续 2 轮」达成 + 接线落地**：一致率主判（16 章 v2.80）轮1 重读 + 轮2 同法复校均总体 PASS → 三维门禁首版实测校准 + NRob 承观察（16 章 v2.82）；余 = G1 校准窗口 + MULTI_DOC 治理线）、簇③语义缓存收官（已合入 main）、簇④ GraphRAG 收官（2026-08-27，五批落地 + 用户侧 E2E 回传通过；分支 `phase5-cluster4-graphrag` 2026-08-28 已合并回 main）**。机器侧就绪、用户侧待跑的运维回传项**唯一源** `docs/project-progress/用户侧待执行项清单.md`。设计依据 `docs/project-implement/README.md`；**过程细节与 E2E 在** `docs/project-progress/` 拆分文档集（索引 = `项目阶段推进任务清单完成记录.md`，按子卷任务行定位，勿整读）。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 百炼 (`qwen3.7-text-embedding`，OpenAI 兼容) · Rerank: `qwen3-rerank` · Judge: `qwen3.7-plus`
- 向量库: pgvector / Milvus 2.6（`kb.vector-store.provider` 切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO（版本指 ECS 服务端，pom 客户端独立）
- 认证: OAuth2 Resource Server (JWT) · Casdoor（前端 PKCE）
- 前端: Vue3 + TS + Element Plus + Pinia + Vite 6
- 压测: Gatling 3.15 Java DSL（kb-loadtest 独立模块，gatling:test 显式触发）

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse/BusinessException/Constants/TextSanitizer
├── kb-domain/         # 8 Entity + 8 Repository + 6 枚举 + schema.sql + db/migration/（Flyway V1 基线）
├── kb-infrastructure/ # vectorstore/（双向量库条件装配）、MinIO、elasticsearch/、parsing/（DocMind+OCR）
├── kb-etl/            # MinIO→SmartParsingRouter(NATIVE/DEEP/OCR)→切分→PG→向量化→ES 双写
├── kb-ai-core/        # 纯 RAG（无工具链）：retriever/（双路+RRF+重排）、advisor/、routing/（主备熔断）、memory/、metrics/、ragAgentChatClient
├── kb-ai-agent/       # Agent 事务域：tool/（Mock 工具+HITL 账本）、config/（toolAgentChatClient）、service/、mcp/（4.10 三件套+身份守卫）
├── kb-api/            # Controller + SSE + SecurityConfig + JwtUtils（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（Chunk 运维与重建 + Bad Case 闭环，kb-api 聚合）
├── kb-eval/           # EvalRunner + 探针 + Golden Dataset(267=干净110+注入127+多跳30) + CI 门禁
├── kb-loadtest/       # Gatling 压测四场景 + StubChatServer 生成桩（簇⑥ 批5，显式触发）
├── frontend/          # Vue3（Login/Chat 溯源对话/Documents/Debug 检索台/Chunks 观测台/Admin 运维中心五 Tab）
└── docs/              # 设计章 + 进度
```

## 运行环境

- **基础设施托管于 ECS**（均远程，本地无需搭建）。
- **API 端口 8090**（8080 被占，`SERVER_PORT=8090`）；前端 `.env` BACKEND_URL 配套。
- 环境变量名与默认值见 infra/ai.yml。
- 启动：本地开发 fat jar 直起（坑位㉘）；**生产 ECS 容器化形态（v2.55 簇⑥）**：根 Dockerfile + `infra/docker-compose.app.yml`（禁 latest / healthcheck / AppCDS 训练服务）+ `infra/.env.example` Secrets 模板 + kb-api.service 开机自启，见 17 章 §17.4；前端 `npm run dev`。

## 当前实现要点

**双链路架构**：`ragAgentChatClient`（kb-ai-core，纯检索零工具）+ `toolAgentChatClient`（kb-ai-agent，纯工具零检索 + defaultTools）；请求体 `mode: rag|tool` 显式分流；共享 smartRoutingChatModel / agentChatMemory / 护栏配额 Advisor / RetrievalContext；toolContext 仅 ToolChatService 组装（物理消除 HITL 凭证泄露）；链序见 11.2

**全链路审计**：`AuditTraceAdvisor`(order 10 最外层)挂双链，异步落 kb_audit_log（旁路容错）；捕获被拒请求，三态 SUCCESS/REJECTED(errorCode)/ERROR；query 脱敏、改写查询经装饰器捕获；`rag.audit.enabled` 可关；kb-eval 不挂

**业务指标**：`metrics/AiBusinessMetrics` 注册中心——rag.feedback/retrieval/tool.call/token/routing/guardrail/request/rerank/chunk.*/badcase.* 计数（request.* 与审计三态同语义）+ 双供应商 SLA 族（熔断/接管计数，供 supplier-sla 面板与 KbPrimaryModelDegraded 告警）；不带租户标签（防基数）

**观测地基（簇①）**：Observation → otel bridge → OTLP Langfuse；总开关默认关；内容捕获单源开关；trace 合树 = 双链装配 + contextWrite 桥接 + 双执行器传播包裹；流式主生成 POST 合树已清偿；rerank 观测清偿

**面板与统计（簇②，批2 生产化）**：Grafana 五面板（含 supplier-sla）+ 监控 compose 生产形态 + 告警 14 条自检矩阵（真触发 + promtool 合成单测）；统计 API GET /api/v1/stats/overview|documents/processing（租户守卫）；无指标支撑面板不设

**语义缓存（Phase 5 簇③）**：`CacheCheckAdvisor(460)` 挂路由后门控前（`rag.cache.enabled` 缺省关，关闭态零变化）：命中短路重放+溯源同形 / 未命中流末五闸异步写入；Redis 8 内建搜索引擎经 Redisson RSearch 零新增依赖（租户域隔离 + KNN 余弦 0.95 + docIds TAG 失效反查，失效频道接四处写路径）；指标 `rag.retrieval.cache.*`；N1 = DeepSeek 前缀缓存默认开零代码；§11.9/11.10

**GraphRAG（Phase 5 簇④）**：`rag.graph.enabled` 缺省关，关闭态全族条件装配缺位（双路形态逐字节零变化）。图 = Neo4j Community（第二台 ECS 独占，原生驱动手工装配 `spring.neo4j.*`，不引 SDN）：Entity 节点（确定性 ID = 租户×名×类型 + 描述嵌入 1024 维同源向量索引 + doc/chunk 溯源引用）+ Chunk 锚点（不存内容，PG 事实源）+ MENTIONS/RELATED_TO。抽取 = ETL COMPLETED 终态帧异步派发（旁路不阻断入库，`graph_status` 独立状态机；qwen3.7-plus 结构化 + 令牌桶双档分桶[增量 20/回填 60 次/分/租户，`RateProfile`] + 信号量 3 + 实体嵌入 10 条/批 + 幂等重写 + 孤儿清扫）。图路检索**零 LLM**：查询嵌入 → 向量索引实体匹配 → 1 跳展开（衰减 0.5）→ chunk 反查 + 租户纵深校验；`RrfFusion` N 路泛化三路融合（双路签名委派兼容）；单路容错/超时降级矩阵三路扩展。生命周期：删除清引用/软删翻标记/编辑重抽取；存量回填 `POST /api/v1/admin/graph/backfill`；备份 `neo4j-backup.sh`（停备窗口 + dump 双副本）；多跳验收 = `MULTI_HOP` 分类 AC 通过率 ≥80%（`--eval.draft-multihop` 机器草稿 + 用户审定）；场景 A 阈值 500→600。§10.9/13.3/17.5-17.6/18.5

**Chunk 运维与重建（簇③）**：kb-admin 首建，kb-api fat jar 聚合（禁反向依赖）；租户守卫 @AuthenticationPrincipal Jwt 直消费（不复用 JwtUtils 防成环）。Chunk CRUD：编辑 = 同源消毒 → PG → 异步重嵌入（**delete→add 两步**——Milvus add 非 upsert）+ ES 覆写（簇④ 联动：编辑触发文档图重抽取、软删/恢复翻转图锚点标记）；软删委派 C1；恢复经重嵌入；守卫 fail-closed。重建：ReindexGateway 委派 reparse（PG 事实源全量重解析 + ES 孤儿清扫；Redis 租户域任务表）

**Bad Case 运营闭环（簇④）**：kb-admin 四端点——审计日志多条件查询联查 / 根因四分类标注 / Golden 回灌 Git Ops 通道（bc-{auditLogId} upsert 幂等）/ 反馈处理态。守卫：跨租户/不存在一律 AUDIT_LOG_NOT_FOUND。前端 /admin 运维中心**五 Tab**（末位护栏词表面板）

**MCP Server（簇⑤ 4.10）**：`spring-ai-starter-mcp-server-webmvc` 落 kb-api（Streamable HTTP `/mcp`，authenticated）；`McpKnowledgeTools` 三件套落 kb-ai-agent（search/get_document 直调 / ask 全链复用；独立 `mcp-` 会话）；`McpIdentityGuard` 物化 RetrievalContext（IDENTITY_INCOMPLETE / MCP_SCOPE_DENIED 治理）；容器无 ToolCallback Bean（HITL 不漏进 MCP）；独立限流桶 120/60s fail-open + 轻量审计；§11.8

**平台层加固（安全簇②）**：CORS 白名单（allowCredentials 显式 false）；上传 50/60MB + chat body 1MB 超限 413；CSP/frameOptions/HSTS 显式钉；actuator include 白名单钉死；dependency-check+CycloneDX（**NVD API key 强制**；B5 残留唯余 B5-4 kotlin 待上游 GA，milvus 服务端误判族级抑制）；台账 12 §12.9 / 17 §17.3

**PII 识别器注册表（安全簇③）**：kb-commons `security/pii`——每类型独立识别器，七类（手机/身份证/邮箱/银行卡 Luhn/座机/车牌/IPv4）；**TextSanitizer.maskPii 退役**——单一实现源迁 Spring 单 Bean，对话链/ETL/审计/MCP/入口日志同实例；配置族 `rag.guardrail.pii.{type}.enabled` 缺省全开；NAME/ADDRESS 默认关；输出回显只计数不替换；kb-eval 干净集零命中门禁；§12.10

**意图路由**：`QueryRoutingAdvisor`(440) 双层分类（正则快路 / 分类+改写合并单次调用预写）→ skipRetrieval；`RetrievalGateAdvisor`(500) 门控包裹检索链——skip 旁路携记忆直答，fail-open 回落；可关

**工具链与 HITL（kb-ai-agent）**：`EnterpriseMockTools` 契约对齐真实 OA/ERP；读工具自动执行、写工具 HITL 三段式（挂起 approvalId → approve → 二次对话带 `approvedToolCallId` 一次性消费）；Redis 账本 TTL 10 分钟 + tenant/user 绑定，故障 fail-closed；**ToolCallingAdvisor 自建 order 1000**

**多模型路由**：`SmartRoutingChatModel`（@Primary）包装主模型 DeepSeek V4（starter 经 `spring.ai.model.chat=none` 让位；include_usage 流式计账）+ 备用 `fallbackChatModel`（qwen3.7-plus 百炼）；熔断三态无锁原子 + SLA 计数；失败即切，流式整段重发备用流；异构备用须重建 Prompt（坑位⑭）；`rag.routing.fallback.enabled=false` 单模型透传

**检索与对话链路**

- 主链路：`RetrievalAugmentationAdvisor`(500) = CompressionQueryTransformer（`rag.retrieval.rewrite.enabled` 默认开）→ `HybridDocumentRetriever` 多路并行（向量+BM25[+Graph，簇④ 条件在场]，租户/软删过滤，5s 超时单路降级）→ `RrfFusion`(K=60，N 路泛化) → `RerankDocumentPostProcessor`（qwen3-rerank，故障降级截断）→ `ContextualQueryAugmenter`（**编号化 formatter** 锚定 [ref-N] + 空证据拒答）；参数收编 `rag.retrieval.*`；多查询扩展关
- **RetrievalContext 参数链（核心模式）**：每请求纯实例，Controller 创建并填 tenantId/userId → advisor 参数 `CONTEXT_KEY` → 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末直读推 TRACE
- SSE 协议：`/chat/stream` 无名 TOKEN/ERROR/DONE（DONE 为 JSON {messageId,traceId}）+ 命名 TRACE（三路溯源与 [ref-N] 对齐）/TOOL_CALL（仅 tool 链）
- 前端对话窗：sessionId 多轮 + rag/tool 切换 + TOOL_CALL 审批卡片
- 租户隔离 fail-closed 两层：① 入口身份守卫（tenantId 缺失抛 `IDENTITY_INCOMPLETE`）；② 检索器有 ctx 无租户返回空多路零触达
- 护栏与配额：`InputSanitizeAdvisor`(300) 归一化+PII 掩码（七类）+注入拦截；`SemanticInjectionAdvisor`(320) **L2 语义判定**（备用模型二判，fail-open 回落 L1；簇②批5 路径 b【剥壳判据】：包裹手段不改变裁决，剥壳后意图为准）；`OutputGuardrailAdvisor`(110) 黑名单整段替换、**流式聚合后验**、PII 回显观察；`TokenBudgetAdvisor`(30) 租户日账本；`RateLimitAdvisor`(100) Redisson 每租户令牌桶；配额码统一 429；**Redis 故障 fail-open（配额）/ fail-closed（审批账本）**；**间接注入扫描**：rerank 前 warn/exclude 双策略；§12.8
- **词表工程（簇①）**：词项模型（value 逐条编码加载层解码）+双源合并（结构化∪CSV；外部 file: 源整文件覆盖内置基线）；REGEX 模式轨；带外导入脚本（AI 零接触词面）；**FLAG 观察**：命中只计数+审计标记，新词默认 FLAG 零误伤方转 BLOCK；§12.7。**热重载（簇⑥ F1）**：单一词表双 volatile 快照原子替换（fail-keep）+ 双触发（pub/sub + file: mtime 轮询回落）；**词表 DB 单轨**：`rag.guardrail.rules.source=file|db`（缺省 file=回滚阀门；kb-eval 恒 file）；kb_guardrail_rule 唯一事实源 + CRUD 只收 valueB64 + POST /reload + 编码 YAML 存档；前端第五 Tab 写路径；§12.7
- **用户反馈闭环**：POST /api/v1/feedback（messageId upsert 可改评；归属经 message→session 校验 fail-closed）+ Bad Case 查询；audit_log.feedback 凭 trace_id 回填
- 多轮记忆：`agentChatMemory` 显式装配 RedisChatMemoryRepository（**REDIS_DB 必须 0**，坑位⑦）；`FaultTolerantChatMemory` 降级；窗口 20 条；PG 归档异步旁路；历史会话续聊回填；kb-eval 零 Redis 依赖
- 评估（kb-eval）：探针 `eval.probe`=auto/vector/hybrid/chain（chain 须配 `eval.chain-probe.tenant-id`）；Golden 267（干净 110 + 注入 127 + 多跳 30）——`MULTI_HOP` 分区（簇④，`multihop-qa.json` 2026-08-27 审定回写 30 例，门禁 = AC≥4.0 通过率 ≥80%，样本 30≥5 生效态）；门禁三分区契约（16 章）——L1 防域（DIRECT+ENCODING_BYPASS）≥95% / L2 防域（JAILBREAK+MULTILINGUAL）≥90%（力判联合链默认关）/ 观察集只报告；L2 原始裁决观测 = `EvalResult#l2RawVerdict` 五态（PASS/SUSPECT/BLOCK/FAIL_OPEN/NOT_JUDGED，经 `VERDICT_SINK_KEY` 回传，快照+报告分布行）；干净集 BLOCK+FLAG 零命中门禁；**间接注入评估（D3）**：默认关；**Phase 5 四新指标**（簇②）：`eval.metrics.*` 已接线门禁（16 章 v2.82）：AC≥4.0 / CA≥0.85 首版 / HR≤0.05 三维 + NRob 承 M3 观察（κ 降观察）；A/B = 快照 + eval-diff 内容盲；导出 = JSONL SFT/DPO（kb-admin，审计过滤+PII 掩码）

**压测资产（kb-loadtest，簇⑥ 批5）**：Gatling 3.15.1 Java DSL（gatling:test 显式触发）；四场景 = A 检索真压 / B 生成桩压（内置纯 JDK StubChatServer 零计费）/ C 真实 LLM TTFT·TPOT（计费敏感缺省关）/ D SSE 多轮会话；语料 = Golden 干净集按 ID 引用（注入集零接触）；执行步骤清单 LT1；方法论 15 §15.4 / 基线 18 §18.4

**解析支线**：SmartParsingRouter 三路由（非 PDF→NATIVE Tika（PPTX/XLSX 亦走此路）/ 默认或 `parseRoute`→DEEP DocMind / 密度<50 字符/页→OCR；自动失败回落，显式失败上抛）；DocMind 表格 HTML 在 `llmResult`；HtmlProtectingSplitter 保护 table/img + heading_path；**Contextual 语境增强默认开**；chunk 确定性 ID；向量化 10 条/批

**基础设施**

- 上传/ETL：`DocumentService`（PDF/DOCX/PPTX/XLSX/MD/TXT/HTML 白名单，仅收 OOXML 新格式，ContentType 校验唯一拦截面 → MinIO → kb_document）；`DocumentEtlService`（解析→切分→**SanitizingTransformer**（S4+PII 入库消毒：`injection_hit` 打标不阻断，MinIO 原件保留）→kb_chunk→向量化→ES 双写）；**增量重入库**：reparse/replace + version + REINDEXING 占用 + CLEANUP 蓝绿 diff
- 认证：`SecurityConfig`（actuator health/info/prometheus/metrics permitAll，/api/** authenticated，其余 denyAll，无状态）；`JwtUtils` Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁原生 auto-config，按 `kb.vector-store.provider` 条件装配；**pgvector 钉 idType=TEXT**（默认 UUID 致 delete 静默失效）
- 配置：kb-api application.yml 经 `spring.config.import` 导入 infra + ai yml；**Redis 连接单一来源**：application-infra.yml `spring.data.redis.*` 被 Redisson 与会话记忆 Jedis 共消费，**不可移除**；**Neo4j 连接**（簇④）：application-infra.yml `spring.neo4j.*`（NEO4J_* env），手工装配 Driver 受 `rag.graph.enabled` 门控；生产 `bolt+s://<域>:7687` 经 nginx `stream` 块 L4 TLS 终结（坑位㊱）+ 闲置连接出借前探活（坑位㊴）
- **多 ChatClient Bean 纪律**：注入点必须显式 `@Qualifier`（chatClient/ragAgent/toolAgent/evalGuardrail 四 Bean）；新增 Advisor 核对 order 与 11.2 链序表一致
- 测试：全模块单测绿 + kb-eval 34 Testcontainers IT（含 Neo4j 网关真跑 IT，镜像钉生产同版本 5.26.29；`mvn verify -pl kb-eval -am`，Docker 必需，无则 -DskipITs）

## 注意事项

- Maven 4 **单模块构建需 `-am`**（兄弟模块不在本地仓库）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`；**ddl-auto=validate**：实体新增字段缺列启动即失败。**Flyway 迁移版本化**：schema 变更先写 `db/migration/V(N+1)__*.sql` 再同步 schema.sql 快照（双源守卫单测）；现网库经 baseline-on-migrate 首跑登记；kb-eval 主上下文关、IT 重开；手工 ALTER 终结
- 父 POM dependencyManagement 预埋后续依赖；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递 4.38.0 覆盖 → `.entity()` NoClassDefFoundError）
- pgvector 需先以 superuser `CREATE EXTENSION IF NOT EXISTS vector;`
- Milvus 原生混合检索否决（10 §10.0）；检索为 Spring AI 2.0 模块化 RAG
- **实证坑**（全量台账 ①-㊵ 见 19 章附录 E）：② allowEmptyContext=true 即空证据自由作答，拒答需 false+空证据模板；⑦ **自动配置让位陷阱**：用户 ChatMemory Bean 先注册时静默回退 InMemory，须显式装配；⑬ **Boot 4.1 迁 Jackson 3**：注入 tools.jackson 命名空间 JsonMapper（旧命名空间无 Bean）；⑭ **跨厂商路由 Prompt 屏障**：转发异构备用前须以备用自身 options 重建 Prompt；⑮ **qwen 商业版默认开思考**（20-60s/调用）——须 enable_thinking=false；㉗ **流式 trace 双坑**：builder 单参 = NOOP registry 须显式传；adviseStream 切线程 ThreadLocal 不跨——父观测经 Reactor Context 键传递 + Controller contextWrite 兜底；㉘ **spring-boot:run 静默跑兄弟模块旧 jar**——改动须先 install；㉙ **@Query 可选参数 `(:p IS NULL OR ...)` PG 预编译雷**——统一 Specification 动态谓词；㉚ **MCP Streamable 须显式钉 `protocol: STREAMABLE`**——缺省静默装配 SSE；㉛ **CorsConfiguration.allowCredentials 缺省 null 非 false**；㉜ **dependency-check 13.x NVD API key 强制**（无 key 无回落）；㊱ **Bolt 禁经 nginx http 块反代**——二进制协议须 `stream` 块 L4 TLS 终结（443 被业务占用另择端口，`proxy_timeout` 放宽）；㊲ **多构造器类须显式 @Autowired 定夺**（生产+测试桩并存即「No default constructor」）；㊳ **CompletableFuture 轮询超时 ≠ 中断**——TimeoutException 误置中断标志即热自旋挤爆堆 + 同线程可中断调用全线失败（回填 OOM 根因）；㊴ **bolt+s 长闲置池化连接被中间层掐断**——首笔事务瞬抛 ServiceUnavailableException（重试自愈），治本 = `withConnectionLivenessCheckTimeout` 出借前探活；㊵ **Cypher 语法仅真库解析期可验且缺陷分层潜伏**——推导式语序错遭拒解（`Invalid input '|'`）、MATCH 模式元素禁属性访问（`(cand.ent)` 非法，须 WITH 先展开——生产被层一拦截未暴露，真跑 IT 挖出），mock 单测盲视，治本 = 网关真跑 IT；㊶ **Neo4j 驱动不容线程中断**——检索路超时 `cancel(true)` 中断阻塞于驱动的线程 → 杀弃 bolt 连接（「Thread interrupted while running query in transaction」）+ 负载下池抖动，治 = 非打断式 `cancel(false)`（弃任务经服务端事务超时收敛）；另测试层：Spring 7 MockHttpServletRequest.setContentLength 已移除，用 setContent 实体字节
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal（异步完结后作用域代理不可解析、Reactor 线程不继承）；CONVERSATION_ID 同理

## 开发工作流约定（用户定案）

文档是项目的 DNA：**功能实现/修 bug 校验后先更新文档再提交代码**，不可颠倒遗漏：

1. **设计回写**：实证性设计修正回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记）
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行 + 顶部日期状态行
3. **CLAUDE.md 同步**：受影响的架构事实（只记架构事实，过程细节入进度文档，控制体积 ≤24KB）
4. **git 提交**：一功能一提交（代码 + 文档同批），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前源码级核验（API 形态/契约/默认行为），不确定搜索官方文档，先核验再落码
6. **通盘思考优先**：实现前先审视设计合理性与可维护性，有更优方案先与用户定案，再实现并回写设计
7. **Token 与会话纪律**：① **功能点即会话边界**——单功能闭环后主动提示 `/compact` 或新开会话；② **交付 E2E 前是压缩最佳时机**（离开 >5 分钟缓存失效）；③ **分段读取**——设计文档只读相关小节，进度文档按任务行编辑；④ **构建静音**——`mvn -q --no-transfer-progress`，失败只读 surefire 报告；⑤ **日志按关键行提取**（grep/tail，勿整读）；⑥ **大范围探索委派 Explore 子代理**，只回结论
8. **E2E 自测形态（定案）**：不启动服务；功能批完成即交付测试步骤 + 文档回写 + 提交，用户自测结果下轮回传更新 E2E 记录
9. **敏感词交付纪律（红线，全程强制）**：任何产出物（方案/代码/配置/词表/攻击语料/E2E 步骤）不得含字面攻击载荷——攻击内容仅以族系名/结构描述/样本 ID 表达；安全词表与攻击样本逐条编码存储（加载层解码，防后续会话读取触发上游注入检测致 400 block + 上下文污染）；配置键/指标名/类名不含攻击语义字面。全文见 `docs/project-optimization/安全加固专项优化方案（调研实证版）.md` §7
