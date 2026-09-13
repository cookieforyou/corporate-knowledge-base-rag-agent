# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台：文档解析、混合检索（向量+BM25[+Graph] RRF 三路）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：此前完成：Phase 1-4、优化冲刺、安全加固专项。**Phase 5（项目最后阶段）**：基线 `docs/project-optimization/Phase 5 复审与规划方案（调研实证版）.md`；模型层批B 主模型 GLM-5.3-Flash 收官（门禁 CA≥0.75/HR≤8%，基线 md1-final-3，生产 temperature 0.2 + effort low）。**Phase5簇①-⑤ 全部收官**（收尾清零/评估进化/语义缓存/GraphRAG/Agent 编排）。**Phase5簇⑥ 产品化收尾推进中**：体验批/补强收官；钉钉批1 收官；A2A 批2 机器侧就绪（E2E 待用户侧）；余归档/文档收口——基线 = sub-cluster-progress/Phase5簇⑥ 实施方案。**用户侧待执行项唯一源** `docs/project-progress/用户侧待执行项清单.md`。设计依据 `docs/project-implement/README.md`；**过程细节与 E2E 在** `docs/project-progress/` 拆分文档集（索引 = `项目阶段推进任务清单完成记录.md`，按子卷任务行定位，勿整读）。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.1 GA + Maven 4
- LLM: 主答双形态（GLM-5.3-Flash 缺省 / DeepSeek V4 回落，`rag.routing.primary.provider` 一切即切）· Embedding: 百炼 (`qwen3.7-text-embedding`，OpenAI 兼容) · Rerank: `qwen3.7-text-rerank` · 辅助族（备用/路由改写/L2 二判/Judge/图抽取/ETL 语境增强）: `qwen3.8-flash`
- 向量库: pgvector / Milvus 2.6（`kb.vector-store.provider` 切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO（版本指 ECS 服务端，pom 客户端独立）
- 认证: OAuth2 Resource Server (JWT) · Casdoor（前端 PKCE）
- 前端: Vue3 + TS + Element Plus + Pinia + Vite 6
- 压测: Gatling 3.15 Java DSL（kb-loadtest 独立模块，gatling:test 显式触发）

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse/BusinessException/TextSanitizer/Constants（全局常量收敛十一分区，kb-eval 值域落模块本地 EvalConstants）
├── kb-domain/         # 8 Entity + 8 Repository + 6 枚举 + schema.sql + db/migration/（Flyway V1 基线）
├── kb-infrastructure/ # vectorstore/（双向量库条件装配）、MinIO、elasticsearch/、parsing/（DocMind+OCR）
├── kb-etl/            # MinIO→SmartParsingRouter(NATIVE/DEEP/OCR)→切分→PG→向量化→ES 双写
├── kb-ai-core/        # 纯 RAG（无工具链）：retriever/（双路+RRF+重排）、advisor/、routing/（主备熔断）、memory/、metrics/、ragAgentChatClient
├── kb-ai-agent/       # Agent 事务域：tool/（Mock 读/写拆类+HITL 账本）、config/（toolAgent+orchestrator 两 ChatClient）、orchestration/（Phase5簇⑤ 编排：TaskTool+SubAgentRegistry+KnowledgeSearchTools）、service/、mcp/（4.10 三件套+身份守卫）、dingtalk/（5.12 群机器人）、a2a/（批2 A2A 治理域）
├── kb-api/            # Controller + SSE + SecurityConfig + JwtUtils（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（Chunk 运维与重建 + Bad Case 闭环，kb-api 聚合）
├── kb-eval/           # EvalRunner + 探针 + Golden Dataset(267=干净110+注入127+多跳30) + CI 门禁
├── kb-loadtest/       # Gatling 压测四场景 + StubChatServer 生成桩（Phase4簇⑥ 批5，显式触发）
├── frontend/          # Vue3（Login/Chat 溯源对话/Documents/Debug 检索台/Chunks 观测台/Admin 运维中心五 Tab）
└── docs/              # 设计章 + 进度
```

## 运行环境

- **基础设施托管于 ECS**（均远程，本地无需搭建）。
- **API 端口 8090**（8080 被占，`SERVER_PORT=8090`）；前端 `.env` BACKEND_URL 配套。
- 环境变量名与默认值见 infra/ai.yml。
- 启动：本地开发 fat jar 直起（坑位㉘）；**生产 ECS 容器化形态（v2.55 Phase4簇⑥）**：根 Dockerfile + `infra/docker-compose.yml`（全栈 app+监控，禁 latest/healthcheck）+ `infra/.env.example` Secrets 模板 + kb-stack.service 全栈自启，见 17 章；手册 docs/delivery/；前端开发 `npm run dev`，生产 = 宿主 nginx 同源反代（`infra/nginx/frontend.conf`）。

## 当前实现要点

**三链路架构**：`ragAgentChatClient`（kb-ai-core，纯检索零工具）+ `toolAgentChatClient`（kb-ai-agent，纯工具零检索 + defaultTools）+ `orchestratorChatClient`（kb-ai-agent，**Phase5簇⑤ 编排链**，`rag.orchestrator.enabled` 缺省关）；请求体 `mode: rag|tool|agent` 显式分流（agent 关闭态 400 ORCHESTRATOR_DISABLED）；共享 smartRoutingChatModel / agentChatMemory / 护栏配额 Advisor / RetrievalContext；toolContext 仅 ToolChatService / AgentOrchestratorService 组装（物理消除 HITL 凭证泄露）；链序见 11.2/§11.5.5

**Multi-Agent 编排（Phase5簇⑤ 收窄版）**：Orchestrator-Workers——主 Agent 仅持 `TaskTool` 委派工具（SSE TOOL_CALL/审计/rag.tool.call.* 零变更）；`orchestration/` = SubAgentSpec（工具集不含 task 防递归）/ SubAgentRegistry（静态注册 + roster 注入主 Agent prompt = 真实工具挂接点）/ TaskTool（身份三键下传、凭证不下传、失败文本化回流、非打断式超时、委派预算硬闸）/ KnowledgeSearchTools（检索同构 MCP 零 LLM，治理:收敛+截断+检索闸）/ TaskBoundaryAdvisor(420 边界注记)+memory-enabled 逃生舱；三子代理差异化模型；Mock 拆 Read/Write；指标 `rag.orchestrator.*`；真实工具挂接契约六条 §11.5.5

**全链路审计**：`AuditTraceAdvisor`(order 10)挂双链，异步落 kb_audit_log（旁路容错）；三态 SUCCESS/REJECTED(errorCode)/ERROR；query 脱敏、改写经装饰器捕获；`rag.audit.enabled` 可关；kb-eval 不挂

**业务指标**：`metrics/AiBusinessMetrics` 注册中心——rag.feedback/retrieval/tool.call/token/routing/guardrail/request/rerank/chunk.*/badcase.* 计数（request.* 与审计三态同语义）+ 双供应商 SLA 族（熔断/接管计数）；不带租户标签（防基数）

**观测地基（Phase4簇①）**：Observation → otel bridge → OTLP Langfuse；总开关默认关；内容捕获单源开关；trace 合树 = 双链装配 + contextWrite 桥接 + 执行器传播

**面板与统计（Phase4簇②）**：Grafana 五面板（含 supplier-sla）+ 监控 compose 生产形态 + 告警 14 条自检矩阵（真触发 + promtool 合成单测）；统计 API GET /api/v1/stats/overview|documents/processing（租户守卫）

**语义缓存（Phase5簇③）**：`CacheCheckAdvisor(460)` 挂路由后门控前（`rag.cache.enabled` 缺省关，关闭态零变化）：命中短路重放+溯源同形 / 未命中流末五闸异步写入；Redis 8 内建搜索经 Redisson RSearch 零新增依赖（租户隔离 + KNN 0.95 + docIds TAG 失效反查接四处写路径）；指标 `rag.retrieval.cache.*`；§11.9/11.10

**GraphRAG（Phase5簇④）**：`rag.graph.enabled` 缺省关（关闭态全族条件装配缺位，双路逐字节零变化）。图 = Neo4j Community（第二台 ECS 独占，原生驱动手工装配不引 SDN）：Entity 节点（确定性 ID + 描述嵌入同源向量索引 + doc/chunk 溯源）+ Chunk 锚点 + MENTIONS/RELATED_TO。抽取 = ETL COMPLETED 帧异步派发（`graph_status` 独立状态机；qwen3.8-flash 结构化 + 令牌桶双档 + 嵌入批量 + 幂等重写 + 孤儿清扫）。图路检索**零 LLM**：查询嵌入→实体匹配→1 跳展开→chunk 反查 + 租户纵深；`RrfFusion` 三路融合；单路容错降级。生命周期：删除清引用/软删翻标记/编辑重抽取；回填 `POST /api/v1/admin/graph/backfill`；备份 `neo4j-backup.sh`；MULTI_HOP AC≥80%。§10.9/13.3/17.5-17.6/18.5

**Chunk 运维与重建（Phase4簇③）**：kb-admin 首建（kb-api 聚合禁反向依赖；Jwt 直消费防成环）。Chunk CRUD：编辑 = 同源消毒→PG→异步重嵌入（**delete→add 两步**，Milvus 非 upsert）+ ES 覆写（联动图重抽取/锚点翻转）；守卫 fail-closed。重建：ReindexGateway 委派 reparse（PG 事实源全量重解析 + ES 孤儿清扫；Redis 租户域任务表）

**Bad Case 运营闭环（Phase4簇④）**：kb-admin 四端点——审计多条件查询 / 根因四分类标注 / Golden 回灌 Git Ops（bc-{auditLogId} upsert 幂等）/ 反馈处理态；跨租户/不存在一律 AUDIT_LOG_NOT_FOUND。前端 /admin 五 Tab（Tab 懒加载，§12.12）

**MCP Server（Phase4簇⑤ 4.10）**：starter-webmvc 落 kb-api（Streamable HTTP `/mcp` authenticated）；`McpKnowledgeTools` 三件套落 kb-ai-agent（search/get_document 直调，ask 全链复用，`mcp-` 会话）；`McpIdentityGuard` 物化 RetrievalContext（IDENTITY_INCOMPLETE/MCP_SCOPE_DENIED）；容器无 ToolCallback Bean（HITL 不漏 MCP）；独立限流桶 fail-open + 轻量审计；§11.8

**钉钉群机器人（Phase5簇⑥ 5.12）**：`rag.dingtalk.enabled` 缺省关（服务域恒装配 + Stream 客户端 SmartLifecycle 条件装配）；**WebSocket 出站长连接（无入站端点，SecurityConfig/安全组零改动）**；开启态三要素 fail-closed（服务账号单租户）；复用 `chatRag` 同步链零减配，回复 markdown 含 [ref-N] 溯源附录（final trace 与 formatter 编号对齐）；监听器须匿名类（坑㊾）；会话域 `dingtalk-{conversationId}`；独立限流桶 + 轻审计 mode=dingtalk + 指标 `rag.dingtalk.*`；非打断式超时；SDK shaded jar 零传递
**A2A 协议端点（Phase5簇⑥ 批2）**：`rag.a2a.enabled` 缺省关（Controller 条件装配 404 缺位）；a2a-java SDK Quarkus/CDI 绑定判负 → **自研协议层（零新增依赖）**：v1.0 JSON-RPC `SendMessage` 同步应答 + Agent Card 发现（`/.well-known/agent-card.json`）；JWT 认证（/mcp 同款 matcher）+ 身份守卫 fail-closed；复用 `chatRag`（会话域 `a2a-{contextId}`）；错误码 -32009/-32601/-32602/-32603；指标 `rag.a2a.*`

**平台层加固（安全簇②）**：CORS 白名单（allowCredentials 显式 false）；上传 50/60MB + chat body 1MB 超限 413；CSP/frameOptions/HSTS 显式钉；actuator include 白名单钉死；dependency-check+CycloneDX（**NVD API key 强制**；B5 残留唯余 B5-4 kotlin 待上游 GA，milvus 服务端误判族级抑制）；台账 12 §12.9 / 17 §17.3

**PII 识别器注册表（安全簇③）**：kb-commons `security/pii`——每类型独立识别器，七类（手机/身份证/邮箱/银行卡 Luhn/座机/车牌/IPv4）；**TextSanitizer.maskPii 退役**——单一实现源迁 Spring 单 Bean，对话链/ETL/审计/MCP/入口日志同实例；配置族 `rag.guardrail.pii.{type}.enabled` 缺省全开；NAME/ADDRESS 默认关；输出回显只计数不替换；kb-eval 干净集零命中门禁；§12.10

**意图路由**：`QueryRoutingAdvisor`(440) 双层分类（正则快路 / 分类+改写合并单次调用）→ skipRetrieval；`RetrievalGateAdvisor`(500) 门控包裹检索链（skip 旁路携记忆直答，fail-open 回落）；可关；路由/改写模型经局部 Builder 挂 fallback（不注册 Bean 防顶全局）

**工具链与 HITL（kb-ai-agent）**：`EnterpriseMockReadTools`/`EnterpriseMockWriteTools`（Phase5簇⑤ 拆类，契约对齐真实 OA/ERP）；读工具自动执行、写工具 HITL 三段式（挂起 approvalId → approve → 二次对话带 `approvedToolCallId` 一次性消费）；Redis 账本 TTL 10 分钟 + tenant/user 绑定，故障 fail-closed；**ToolCallingAdvisor 自建 order 1000（tool/orchestrator 两链共享）**

**多模型路由**：主模型双形态 `rag.routing.primary.provider`=glm|deepseek（缺省 glm = GLM-5.3-Flash 强制思考 + effort 透传；deepseek 思考关）互斥条件装配 + `primaryChatModel` 桥（非法值启动失败）；`SmartRoutingChatModel`（@Primary）包 primary + 备用 `fallbackChatModel`（qwen3.8-flash）；熔断三态无锁原子 + SLA 计数；失败即切，流式整段重发（异构须重建 Prompt 坑位⑭）；`rag.routing.fallback.enabled=false` 单模型透传；deepseek starter 退役（chat=none 防御位）

**检索与对话链路**

- 主链路：`RetrievalAugmentationAdvisor`(500) = CompressionQueryTransformer（默认开）→ `HybridDocumentRetriever` 多路并行（向量+BM25[+Graph 条件在场]，租户/软删过滤，5s 单路降级）→ `RrfFusion`(K=60) → `RerankDocumentPostProcessor`（故障降级截断）→ `ContextualQueryAugmenter`（编号化 formatter 锚定 [ref-N] + 空证据拒答）；参数 `rag.retrieval.*`
- **RetrievalContext 参数链（核心模式）**：每请求纯实例，Controller 创建并填 tenantId/userId → advisor 参数 `CONTEXT_KEY` → 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末直读推 TRACE
- SSE 协议：`/chat/stream` 无名 TOKEN/ERROR/DONE（DONE JSON 载荷）+ 命名 TRACE（rag 链溯源 [ref-N]）/TOOL_CALL（tool/agent 委派，实时+流末兜底）/REPLACE（护栏追回）/PROGRESS（三链路进度+心跳）
- 前端对话窗：sessionId 多轮 + rag/tool/agent 三模式切换 + TOOL_CALL 审批/委派卡片
- 租户隔离 fail-closed 两层：① 入口身份守卫（tenantId 缺失抛 `IDENTITY_INCOMPLETE`）；② 检索器有 ctx 无租户返回空多路零触达
- 护栏与配额：`InputSanitizeAdvisor`(300) 归一化+PII 掩码（七类）+注入拦截；`SemanticInjectionAdvisor`(320) **L2 语义判定**（备用模型二判 fail-open，剥壳判据）；`OutputGuardrailAdvisor`(110) 黑名单整段替换+**流式增量放行+REPLACE 追回**+PII 回显观察；`TokenBudgetAdvisor`(30) 日账本；`RateLimitAdvisor`(100) 令牌桶；配额码 429；Redis 故障 fail-open（配额）/fail-closed（审批账本）；间接注入扫描 rerank 前 warn/exclude；§12.8
- **词表工程（安全簇①）**：词项模型（value 逐条编码加载层解码）+ 双源合并（结构化∪CSV；file: 源整文件覆盖）；REGEX 轨；带外导入脚本（AI 零接触词面）；**FLAG 观察**（命中只计数，新词默认 FLAG 方转 BLOCK）；**热重载（安全簇⑥ F1）**：双 volatile 快照原子替换 + pub/sub/mtime 双触发；**DB 单轨** `rag.guardrail.rules.source=file|db`（缺省 file=回滚阀门，kb-eval 恒 file）+ kb_guardrail_rule 唯一事实源（CRUD 只收 valueB64 + /reload）；前端第五 Tab 写路径；§12.7
- **用户反馈闭环**：POST /api/v1/feedback（messageId upsert 可改评；归属经 message→session 校验 fail-closed）+ Bad Case 查询；audit_log.feedback 凭 trace_id 回填
- 多轮记忆：`agentChatMemory` 显式装配 RedisChatMemoryRepository（坑位⑦，显式装配必须保留）；`FaultTolerantChatMemory` 降级；窗口 20 条；PG 归档异步旁路；续聊回填；kb-eval 零 Redis 依赖
- 评估（kb-eval）：探针 `eval.probe`=auto/vector/hybrid/chain；Golden 267（干净 110 + 注入 127 + 多跳 30，MULTI_HOP 门禁 AC≥4.0 通过率 ≥80%）；门禁三分区契约（16 章）——L1 防域 ≥95% / L2 防域 ≥90%（力判联合链默认关）/ 观察集只报告；MULTI_DOC 地板覆写 3.0 + docRecall≥0.5 通过率 ≥0.80；judge 剥壳容错（畸形不静默给分）；干净集 BLOCK+FLAG 零命中门禁；间接注入评估默认关；Phase 5 四新指标已接线门禁：AC≥4.0 / CA≥0.75 / HR≤0.08（md1-final-3/GLM 重锚，16 章）+ NRob 观察；A/B = 快照 + eval-diff 内容盲；导出 JSONL SFT/DPO（kb-admin）

**压测资产（kb-loadtest）**：Gatling Java DSL（gatling:test 显式触发）；四场景 = 检索真压 / 生成桩压（纯 JDK StubChatServer）/ 真实 LLM TTFT·TPOT（缺省关）/ SSE 多轮；语料 = Golden 干净集；§15.4/§18.4

**解析支线**：SmartParsingRouter 三路由（非 PDF→NATIVE Tika / 默认或 `parseRoute`→DEEP DocMind / 密度<50 字符/页→OCR；自动失败回落）；DocMind 表格 HTML 在 `llmResult`；HtmlProtectingSplitter 保护 table/img + heading_path；**Contextual 语境增强默认开**；chunk 确定性 ID；向量化 10 条/批

**基础设施**

- 上传/ETL：`DocumentService`（七格式白名单仅 OOXML，ContentType 唯一拦截面 → MinIO → kb_document）；`DocumentEtlService`（解析→切分→**SanitizingTransformer**（S4+PII 入库消毒，打标不阻断）→kb_chunk→向量化→ES 双写）；**增量重入库**：reparse/replace + version + REINDEXING 占用 + CLEANUP 蓝绿 diff
- 认证：`SecurityConfig`（actuator 白名单 permitAll，/api/** authenticated，其余 denyAll，无状态）；**运维面三层分级（§12.12）**：isAdmin → ROLE_ADMIN，owner=built-in 超管追加 ROLE_SUPER_ADMIN 独占 guardrail 运维端点；`/api/v1/admin/**` hasRole(ADMIN) + 治理写 @PreAuthorize；统计/上传/列表全员；`JwtUtils` Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁原生 auto-config，按 `kb.vector-store.provider` 条件装配；**pgvector 钉 idType=TEXT**（默认 UUID 致 delete 静默失效）
- 配置：kb-api application.yml 经 `spring.config.import` 导入 infra + ai yml；**Redis 单一来源**：application-infra.yml `spring.data.redis.*` 被 Redisson 与会话记忆（占位符桥接）共消费**不可移除**；**Neo4j**（Phase5簇④）`spring.neo4j.*` 手工装配 Driver 受 `rag.graph.enabled` 门控；生产 `bolt+s://` 经 nginx `stream` 块 L4 TLS 终结（坑位㊱）+ 出借前探活（坑位㊴）
- 测试：全模块单测绿 + kb-eval 46 Testcontainers IT（含 Neo4j 网关真跑 IT，镜像钉生产同版本 5.26.29；`mvn verify -pl kb-eval -am`，Docker 必需，无则 -DskipITs）

## 注意事项

- Maven 4 **单模块构建需 `-am`**（兄弟模块不在本地仓库）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`；**ddl-auto=validate**：实体新增字段缺列启动即失败。**Flyway 迁移版本化**：schema 变更先写 `db/migration/V(N+1)__*.sql` 再同步 schema.sql 快照（双源守卫单测）；现网库经 baseline-on-migrate 首跑登记；kb-eval 主上下文关、IT 重开；手工 ALTER 终结
- 父 POM dependencyManagement 预埋后续依赖；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递 4.38.0 覆盖 → `.entity()` NoClassDefFoundError）
- pgvector 需先以 superuser `CREATE EXTENSION IF NOT EXISTS vector;`
- Milvus 原生混合检索否决（10 §10.0）；检索为 Spring AI 2.0 模块化 RAG
- **实证坑**（全量台账 ①-㊽ 见 19 章附录 E）：② allowEmptyContext=true 即空证据自由作答，拒答需 false+模板；⑦ **自动配置让位**：用户 ChatMemory Bean 先注册即静默回退 InMemory，须显式装配；⑬ **Boot 4.1 迁 Jackson 3**：注入 tools.jackson JsonMapper；⑭ **跨厂商路由 Prompt 屏障**：转发异构备用前以备用自身 options 重建 Prompt；⑮ **qwen 商业版默认开思考**（20-60s/调用）须 enable_thinking=false；㉗ **流式 trace 双坑**：builder 单参 = NOOP registry 须显式传；adviseStream 切线程 ThreadLocal 不跨，父观测经 Reactor Context 键 + contextWrite 兜底；㉘ **spring-boot:run 静默跑兄弟模块旧 jar**——改动须先 install；㉙ **@Query 可选参数 `(:p IS NULL OR ...)` PG 预编译雷**——统一 Specification；㉚ **MCP Streamable 须显式钉 `protocol: STREAMABLE`**（缺省装 SSE）；㉛ **allowCredentials 缺省 null 非 false**；㉜ **dependency-check 13.x NVD key 强制**；㊱ **Bolt 禁经 nginx http 块反代**——须 `stream` 块 L4 TLS 终结；㊲ **多构造器类须显式 @Autowired 定夺**；㊳ **CompletableFuture 轮询超时误置中断标志 → 热自旋挤爆堆**（回填 OOM 根因）；㊴ **bolt+s 长闲置池化连接被中间层掐断**——治本 = `withConnectionLivenessCheckTimeout` 出借前探活；㊵ **Cypher 缺陷仅真库解析期可验**（推导式语序 / MATCH 属性访问两形态，mock 盲视）——治本 = 网关真跑 IT；㊶ **Neo4j 驱动不容线程中断**——检索路超时须非打断式 `cancel(false)`（防杀 bolt 连接）；㊸ **配置缺省双源**——@ConfigurationProperties 字段缺省被 application.yml 显式段遮蔽，改缺省须 Java + yml 两源同查；㊹ **yml 插段误挂平级节点 Binder 静默不命中**——插段必核父级链缩进，「配置写了」≠「配置生效」；㊺ **容器内多同类型 Bean 按类型注入歧义**——条件装配新增 ExecutorService 族 Bean → 消费点 found 2 启动失败，治 = 显式 @Qualifier；开启态完整启动是独立验证面；另：Spring 7 MockHttpServletRequest.setContentLength 移除，用 setContent
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal（异步完结后作用域代理不可解析、Reactor 线程不继承）；CONVERSATION_ID 同理
- **多 ChatClient Bean 纪律**：注入点显式 `@Qualifier`；新增 Advisor 核对 order 与 11.2 链序表一致
- **常量收敛纪律**（边界八条见 6 章 §6.3）：Constants 只收**跨类/跨模块共享的协议性字面量**（错误码、检索路名与 metadata 键、对话协议值 mode/SSE 事件/审计三态、JWT claims、MCP 工具名、Bean 名等注册↔消费契约）；有枚举归属的值域以枚举为单一事实源不入 Constants；配置键归 @ConfigurationProperties；日志/描述/提示语与**单类内聚字面**不收敛——新增字面先判归属（跨类契约→Constants 对应分区 / 单类→类内 private static final）再落位

## 开发工作流约定（用户定案）

文档是项目的 DNA：**功能实现/修 bug 校验后先更新文档再提交代码**，不可颠倒遗漏：

1. **设计回写**：实证性设计修正回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记）
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行 + 顶部日期状态行
3. **CLAUDE.md 同步**：受影响的架构事实（只记架构事实，过程细节入进度文档，控制体积 ≤24KB）
4. **git 提交**：一功能一提交（代码 + 文档同批），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前源码级核验（API 形态/契约/默认行为），不确定搜索官方文档，先核验再落码
6. **通盘思考优先**：实现前先审视设计合理性与可维护性，有更优方案先与用户定案，再实现并回写设计
7. **Token 与会话纪律**：① 功能点即会话边界——单功能闭环后提示 `/compact` 或新开会话；② 交付 E2E 前是压缩最佳时机（离开 >5 分钟缓存失效）；③ 分段读取——设计文档只读相关小节，进度文档按任务行编辑；④ 构建静音 `mvn -q --no-transfer-progress`，失败只读 surefire 报告；⑤ 日志按关键行提取（grep/tail）；⑥ 大范围探索委派 Explore 子代理只回结论
8. **E2E 自测形态**：不启动服务；功能批完成即交付测试步骤 + 文档回写 + 提交，用户自测结果下轮回传更新 E2E 记录
9. **敏感词交付纪律（红线，全程强制）**：任何产出物（方案/代码/配置/词表/攻击语料/E2E 步骤）不得含字面攻击载荷——攻击内容仅以族系名/结构描述/样本 ID 表达；安全词表与攻击样本逐条编码存储（加载层解码，防后续会话读取触发上游注入检测致 400 block + 上下文污染）；配置键/指标名/类名不含攻击语义字面。全文见 `docs/project-optimization/安全加固专项优化方案（调研实证版）.md` §7
10. **临时证据路径纪律**：仓库根 `logs/` = 用户侧回传证据临时区（会清除）——文档不引用其下具体文件路径，证据以读数/结论/现象落档
11. **簇 tag 命名规约**：簇编号 tag 必须带阶段前缀且紧凑无空格——`Phase4簇N / Phase5簇N / 安全簇N / 冲刺簇N`（四阶段各自独立成簇编号：优化冲刺/安全加固专项/Phase 4/Phase 5）；新增产出禁用裸「簇N」；历史存量已全量消歧（唯 Flyway V1-V3 + schema.sql 注释因 checksum/同源纪律豁免保留）
