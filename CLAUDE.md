# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台：文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1-3、优化冲刺与安全加固专项（簇①-⑥，v2.53 E2E 通过 08-20）完成；**Phase 4 簇⑥生产加固与压测五批机器侧全收官（08-20~22）**：批1a Flyway（v2.54）/批1b 容器化（v2.55）/批2 监控栈生产化（v2.56，告警 14 条自检矩阵）/批3 灾备最小集（v2.57，PG 备份+恢复演练+99.5% 自检）/批4 trace 残余清偿+SLA+安全组（v2.58）/批5 Gatling 压测（v2.59，kb-loadtest 四场景+生成桩）；各批落点见 7/13/15/17/18 章 + 05 卷任务行。机器侧就绪用户侧待跑项**唯一源** `docs/project-progress/用户侧待执行项清单.md`（F1-F3/M1/DR1/SG1/LT1/G1/G2/E1/B5/D1 含详步骤）。登记缓做项见 04/06 卷与清单（B5 另跟踪、探针校准转下冲刺、S9 不排期）。设计依据 `docs/project-implement/README.md`；**过程细节与 E2E 在** `docs/project-progress/` 拆分文档集（索引 = `项目阶段推进任务清单完成记录.md` → 00 每日进度 / 01-03 Phase1-3 / 04 优化冲刺 / 05 Phase4 含簇⑥ / 06 安全专项 / 07 Phase5；按子卷任务行定位，勿整读）。

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
├── kb-eval/           # EvalRunner + 探针 + Golden Dataset(237=干净110+注入127) + CI 门禁
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

**全链路审计**：`AuditTraceAdvisor`(order 10 最外层)挂双链，异步落 kb_audit_log（旁路容错）；捕获被拒请求，三态 SUCCESS/REJECTED(errorCode)/ERROR；query 脱敏落库、改写查询经装饰器捕获；`rag.audit.enabled` 可关；kb-eval 不挂

**业务指标**：`metrics/AiBusinessMetrics` 注册中心——rag.feedback/retrieval/tool.call/token/routing/guardrail/request/rerank/chunk.*/badcase.* 计数（request.* 与审计三态同语义）+ 双供应商 SLA 族 rag.routing.circuit.opened/half-opened + fallback.invoked（SmartRoutingChatModel 接线，supplier-sla 面板与 KbPrimaryModelDegraded 告警消费）；不带租户标签（防基数）

**观测地基（簇①）**：Observation → otel bridge → OTLP Langfuse；总开关 `management.tracing.export.otlp.enabled` 默认关；内容捕获 `RAG_OBSERVABILITY_LOG_CONTENT` 单源，内容自桥接 gen_ai.prompt/completion（坑位㉔㉕）；trace 合树：双链显式装配 registry + Controller contextWrite 桥接（坑位㉗）+ 检索双执行器传播包裹；**流式主生成 POST 独立 trace 已清偿（批4 v2.58，根因链见 13 章 v2.58）**：SmartRoutingChatModel.stream 订阅期父观测作用域注入，POST span 挂 chat_client 下合树（E2E 裁决留用户侧）

**面板与统计（簇②，批2 生产化）**：Grafana 五面板（含 supplier-sla）+ 监控 compose 落 infra/ 生产形态——四服务 restart/healthcheck/限额/日志轮转 + Grafana 口令 env 化（`GRAFANA_ADMIN_PASSWORD` :? 守卫）+ node_exporter（Host 层告警组 kb-rag-host）+ kb-monitoring.service 开机自启；告警 14 条自检矩阵（6 真触发 + 8 promtool 合成单测 alert-selfcheck/）；统计 API GET /api/v1/stats/overview|documents/processing（租户守卫）；无指标支撑面板不设

**Chunk 运维与重建（簇③）**：kb-admin 首建，kb-api fat jar 聚合（禁反向依赖）；租户守卫 @AuthenticationPrincipal Jwt 直消费（owner claim，不复用 JwtUtils 防成环）。Chunk CRUD：编辑 = 同源消毒 → PG 同步 → 异步重嵌入（**统一 delete→add 两步**——Milvus add 非 upsert 实证）+ ES 覆写；软删委派 C1；恢复经重嵌入；守卫 fail-closed（跨租户 → CHUNK_NOT_FOUND，处理中 → DOC_NOT_READY）。重建：ReindexGateway 委派 reparse（PG 事实源全量重解析 + ES 孤儿清扫；Redis 租户域 FIFO 任务表 TTL 24h fail-closed）

**Bad Case 运营闭环（簇④）**：kb-admin 四端点——GET /admin/audit-logs（多条件 + 期望答案联查）；PUT /audit-logs/{id}/root-cause（四分类，**ECS 先 ALTER**）；POST /badcase/reingest（**Git Ops 文件通道**，id=bc-{auditLogId} upsert 幂等，联动反馈 resolved）；PUT /feedback/{id}/resolved（租户链）。守卫：跨租户/不存在一律 AUDIT_LOG_NOT_FOUND（不泄露存在性）。前端 /admin 运维中心**五 Tab**（末位护栏词表只读面板）

**MCP Server（簇⑤ 4.10）**：`spring-ai-starter-mcp-server-webmvc` 落 kb-api（Streamable HTTP `/mcp`，authenticated）；`McpKnowledgeTools` 三件套落 kb-ai-agent——@McpTool 扫描收编：search/get_document 直调（租户守卫+软删过滤）/ask 全链复用护栏配额审计（独立 mcp- 前缀会话）；`McpIdentityGuard` 请求线程物化 RetrievalContext（owner 空白 IDENTITY_INCOMPLETE；scope 治理 MCP_SCOPE_DENIED）；容器无 ToolCallback Bean（HITL 不漏进 MCP）；独立限流桶 `rag:ratelimit:mcp:{tenant}` 120/60s fail-open + 轻量审计（日志恒开/DB 默认关），超限 429

**平台层加固（安全簇②）**：CORS 白名单 `app.cors.allowed-origins`（env，allowCredentials 显式 false，坑位㉛），与 WS 键独立；上传 multipart 50/60MB + Service 复核 + chat body 1MB，超限统一 413；CSP default-src 'none' + frameOptions DENY + HSTS 显式钉；actuator include 白名单即钉死暴露面；dependency-check+CycloneDX 不绑生命周期（**NVD API key 强制**，SBOM+漏洞基线入档）；台账 12 章 §12.9 / 17 章 §17.3

**PII 识别器注册表（安全簇③）**：kb-commons `security/pii` 包——每类型独立识别器（模式/置信度/掩码策略/enabled）；七类（手机/身份证/邮箱/银行卡 Luhn/座机/车牌/IPv4）；注册序即优先级；**TextSanitizer.maskPii 退役**——单一实现源迁 Spring 单 Bean，对话链/ETL/审计/MCP/入口日志同实例；配置族 `rag.guardrail.pii.{type}.enabled` 缺省全开；C3 NAME/ADDRESS 默认关；输出 PII 回显 FLAG 计数 `rag.guardrail.output.pii.echo` 不替换；kb-eval 干净集零命中门禁；§12.10

**意图路由**：`QueryRoutingAdvisor`(440) 双层分类（正则快路 / 分类+改写单次调用预写）→ skipRetrieval；`RetrievalGateAdvisor`(500) 组合式门控包裹 RAA——skip 旁路携记忆直答，fail-open 回落；`rag.routing.intent.enabled` 可关

**工具链与 HITL（kb-ai-agent）**：`EnterpriseMockTools` 契约对齐真实 OA/ERP；读工具自动执行、写工具 HITL 三段式（挂起 approvalId → approve 端点 → 二次对话带 `approvedToolCallId` 一次性消费）；Redis 账本 TTL 10 分钟 + 一次性消费 + tenant/user 绑定，Redis 故障 fail-closed；确认态经 `.toolContext()`；**ToolCallingAdvisor 自建 order 1000**（自动注册落最外层穿越内层）

**多模型路由**：`SmartRoutingChatModel`（@Primary）包装主模型 DeepSeek V4（starter 经 `spring.ai.model.chat=none` 门控让位勿回写；include_usage 流式计账）+ 备用 `fallbackChatModel`（qwen3.7-plus 百炼端点，凭据回落 DASHSCOPE_API_KEY）；熔断三态无锁原子（rag.routing.circuit）+ SLA 三事件计数经 AiBusinessMetrics；失败即切，流式整段重发备用流；异构备用须重建 Prompt（坑位⑭）；`rag.routing.fallback.enabled=false` 单模型透传

**检索与对话链路**

- 主链路：`RetrievalAugmentationAdvisor`(500) = CompressionQueryTransformer（`rag.retrieval.rewrite.enabled` 默认开）→ `HybridDocumentRetriever` 双路并行（tenant/is_deleted 过滤，5s 超时降级）→ `RrfFusion`(K=60) → `RerankDocumentPostProcessor`（qwen3-rerank 扁平契约，故障降级 fusion_score 截断）→ `ContextualQueryAugmenter`（**编号化 documentFormatter** 锚定 [ref-N] + `allowEmptyContext=false` 空证据拒答）；参数收编 `rag.retrieval.*`（改参须配 kb-eval）；多查询扩展关
- **RetrievalContext 参数链（核心模式）**：每请求纯实例，Controller 创建并填 tenantId/userId → advisor 参数 `CONTEXT_KEY` → 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末直读推 TRACE
- SSE 协议：`/chat/stream` 无名 TOKEN/ERROR/DONE（DONE 为 JSON {messageId,traceId}）+ 命名 TRACE（三路溯源与 [ref-N] 对齐）/ TOOL_CALL（仅 tool 链）
- 前端对话窗：sessionId 多轮 + rag/tool 切换 + TOOL_CALL 审批卡片
- 租户隔离 fail-closed 两层：① 入口身份守卫（tenantId 缺失抛 `IDENTITY_INCOMPLETE`）；② 检索器有 ctx 无租户返回空双路零触达
- 护栏与配额：`InputSanitizeAdvisor`(300) 归一化检测（仅检测不回写）+PII 掩码（七类）+注入拦截（`PROMPT_INJECTION`）；`SemanticInjectionAdvisor`(320) **L2 语义判定**——REGEX 命中∧干词未命中/跨轮拼接信号触发备用模型二判（PASS/SUSPECT/BLOCK，SUSPECT 走 FLAG），fail-open 回落 L1；eval 门禁治 L2 判别力（§12.11）；`OutputGuardrailAdvisor`(110) 黑名单整段替换、**流式聚合后验**、PII 回显观察；`TokenBudgetAdvisor`(30) 租户日账本；`RateLimitAdvisor`(100) Redisson 每租户令牌桶；配额码 RATE_LIMITED/TOKEN_BUDGET_EXCEEDED 统一 429；**Redis 故障 fail-open（配额）/ fail-closed（审批账本）**；**间接注入扫描（簇④ D1）**：rerank 前扫描（剔除不进重排/TRACE），warn/exclude 双策略；**打标降权（D2）默认关**（injection_hit 契约携带，度量后定案）；§12.8
- **词表工程（簇①）**：词项模型（value 逐条编码加载层解码）+双源合并（结构化∪CSV；外部 file: 源整文件覆盖内置基线——**外部路径必须持完整词表文件**）；REGEX 模式轨（领域裸词不入 BLOCK）；带外导入 import_words.py/import_corpus.py（AI 零接触词面）；**FLAG 观察**：命中只计数+审计标记（`rag.guardrail.flagged` + 审计 `guardrail_flags` 列，ECS 先 ALTER），新词默认 FLAG 零误伤方转 BLOCK；输出三分类话术+系统提示金丝雀；§12.7。**热重载（簇⑥ F1）**：`GuardrailRulesRegistry` 单一词表双 volatile 快照原子替换（fail-keep 保旧）+ `GuardrailReloadCoordinator` 双触发（pub/sub `rag:guardrail:reload` + file: mtime 轮询回落；classpath 不轮询；阀门隔离 kb-eval）；五消费方 Observer 化；**F2 端点** GET /admin/guardrail/rules 活视图 + POST /drill 演练（无指标无审计，value 不回显）。**词表 DB 单轨**：`rag.guardrail.rules.source=file|db` 源选择（缺省 file=回滚阀门；kb-eval 恒 file 锁版）；kb_guardrail_rule 唯一事实源（编码 + (side,type,fingerprint) 去重；Seeder 首启迁移文件源全集——Registry 构造早于 Runner 首启空表装 0 条，手工构造文件源勿注 SPI、灌库后主动 reload）；CRUD API 只收 valueB64（新建默认 FLAG）+ POST /reload（本地+pub/sub）；写后编码 YAML 存档导出；前端第五 Tab 写路径；Git Ops 带外保留；§12.7
- **用户反馈闭环**：POST /api/v1/feedback（messageId+userId upsert 可改评；归属 fail-closed，跨域 MESSAGE_NOT_FOUND）+ Bad Case 查询；audit_log.feedback 凭 trace_id 回填
- 多轮记忆：`agentChatMemory` 显式装配 RedisChatMemoryRepository（**REDIS_DB 必须 0**，坑位⑦）；`FaultTolerantChatMemory` 降级；窗口 20 条；PG 归档 `ChatSessionService` 异步旁路；历史会话：会话端点 + 过期续聊回填；kb-eval 零 Redis 依赖
- 评估（kb-eval）：探针 `eval.probe`=auto/vector/hybrid/chain（hybrid 直调检索器、chain 走全链须配 `eval.chain-probe.tenant-id`）；Golden 237（干净 110 + 注入 127）；门禁三分区契约（16 章）——L1 防域（DIRECT+ENCODING_BYPASS）≥95% / L2 防域（JAILBREAK+MULTILINGUAL）≥90%（力判联合链默认关）/ 观察集（ENCODING_OPAQUE）只报告；干净集 BLOCK+FLAG 零命中门禁；**间接注入评估（D3）**：`indirect/` 语料打标自洽 + Judge 抑制率，默认关

**压测资产（kb-loadtest，簇⑥ 批5）**：Gatling 3.15.1 Java DSL（gatling:test 显式触发，*Simulation surefire 域外——根聚合 test 只编译）；四场景 = A 检索真压 P95<500ms / B 生成桩压 50 并发 / C 真实 LLM 10 样本 TTFT·TPOT（计费敏感缺省关 -Dloadtest.c.enabled）/ D 20 并发 SSE 多轮会话；内置 StubChatServer（纯 JDK，OpenAI chunk 全契约）零计费压应用层；SSE 帧判别 jmesPath data.* 直匹配 + [DONE] 原文扫描 + drain 安全窗口（15 §15.4）；语料 = Golden 干净集按 ID 引用（注入集零接触）；参数 -Dloadtest.*；执行步骤清单 LT1

**解析支线**：SmartParsingRouter 三路由（非 PDF→NATIVE Tika / 默认或 `parseRoute`→DEEP DocMind / 密度<50 字符/页→OCR；自动失败回落 NATIVE，显式失败上抛）；DocMind 表格 HTML 在 `llmResult`、正文 `markdownContent`；HtmlProtectingSplitter 保护 table/img + heading_path 落三存储面；**Contextual 语境增强默认开**；chunk 确定性 ID（文档名#序号#增强前原文）；向量化 10 条/批

**基础设施**

- 上传/ETL：`DocumentService`（PDF/DOCX/MD/TXT/HTML 白名单 → MinIO → kb_document）；`DocumentEtlService`（解析→切分→**SanitizingTransformer**（S4+PII 入库消毒：`injection_hit` 打标不阻断，MinIO 原件保留）→kb_chunk→向量化→ES 双写）；**增量重入库**：reparse/replace + version + REINDEXING 占用 + CLEANUP 蓝绿 diff
- 认证：`SecurityConfig`（/actuator/health|info|prometheus|metrics/** permitAll，/api/** authenticated，其余 denyAll，无状态）；`JwtUtils` Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁原生 auto-config，按 `kb.vector-store.provider` 条件装配；**pgvector 钉 idType=TEXT**（默认 UUID 致 delete 静默失效）
- 配置：kb-api application.yml 经 `spring.config.import` 导入 infra + ai yml；**Redis 连接单一来源**：application-infra.yml `spring.data.redis.*` 被 Redisson 与会话记忆 Jedis 共消费，**不可移除**
- **多 ChatClient Bean 纪律**：chatClient（评估）/ragAgentChatClient/toolAgentChatClient/evalGuardrailChatClient，注入点必须显式 `@Qualifier`；新增 Advisor 核对 order 与链序表（11.2）一致
- 测试：全模块单测绿 + kb-eval 33 Testcontainers IT（`mvn verify -pl kb-eval -am`，Docker 必需，无则 -DskipITs）

## 注意事项

- Maven 4 **单模块构建需 `-am`**（兄弟模块不在本地仓库）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`；**ddl-auto=validate**：实体新增字段缺列启动即失败。**Flyway 迁移版本化**：schema 变更须先写 kb-domain `db/migration/V(N+1)__*.sql` 再同步 schema.sql 全量快照（IT init script 专用 + SchemaDualSourceConsistencyTest 表集守卫）；ECS 现网库经 baseline-on-migrate 首跑登记（V1 幂等零变更）；kb-eval 主上下文 flyway 关、IT 重开作 baseline 回归；手工 ALTER 终结
- 父 POM dependencyManagement 预埋后续依赖；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递 4.38.0 覆盖 → `.entity()` NoClassDefFoundError）
- pgvector 需先以 superuser `CREATE EXTENSION IF NOT EXISTS vector;`
- Milvus 原生混合检索否决（10 §10.0）；检索为 Spring AI 2.0 模块化 RAG
- **实证坑**（全量台账 ①-㉜ 见 19 章附录 E）：② allowEmptyContext=true 即空证据自由作答，拒答需 false+空证据模板；⑦ **自动配置让位陷阱**：用户 ChatMemory Bean 先注册时静默回退 InMemory，须显式装配；⑬ **Boot 4.1 迁 Jackson 3**：注入 tools.jackson.databind.json.JsonMapper（旧命名空间无 Bean）；注解仍 com.fasterxml.jackson.annotation；⑭ **跨厂商路由 Prompt 屏障**：转发异构备用前须以备用自身 options 重建 Prompt；⑮ **qwen3.5/3.6/3.7 商业版默认开思考**（20-60s/调用）——须 enable_thinking=false；㉗ **流式 trace 双坑**：builder(chatModel) 单参 = NOOP registry 须显式传 observationRegistry；adviseStream 切线程 ThreadLocal 不跨——父观测经 Reactor Context micrometer.observation 键传递 + Controller contextWrite 兜底（全链 13 章）；㉘ **spring-boot:run 静默跑兄弟模块已安装旧 jar**——改动须先 install；㉙ **@Query 可选参数 `(:p IS NULL OR ...)` PG 预编译雷**——多选项过滤统一 Specification 动态谓词；㉚ **MCP Streamable 传输须显式钉 `spring.ai.mcp.server.protocol: STREAMABLE`**——缺省静默装配 SSE 致 /mcp 无路由；㉛ **CorsConfiguration.allowCredentials 缺省 null 非 false**；㉜ **OWASP dependency-check 13.x NVD API key 强制**（无 key UpdateException 无回落）；另测试层：Spring 7 MockHttpServletRequest.setContentLength 已移除，用 setContent 实体字节
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal（异步完结后作用域代理不可解析、Reactor 线程不继承）；CONVERSATION_ID 同理

## 开发工作流约定（用户定案）

文档是项目的 DNA：**功能实现/修 bug 校验后先更新文档再提交代码**，不可颠倒遗漏：

1. **设计回写**：实证性设计修正回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记）
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行 + 顶部日期状态行
3. **CLAUDE.md 同步**：受影响的架构事实（只记架构事实，过程细节入进度文档，控制体积 ≤20KB）
4. **git 提交**：一功能一提交（代码 + 文档同批），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前源码级核验（API 形态/契约/默认行为），不确定搜索官方文档，先核验再落码
6. **通盘思考优先**：实现前先审视设计合理性与可维护性，有更优方案先与用户定案，再实现并回写设计
7. **Token 与会话纪律**：① **功能点即会话边界**——单功能闭环后主动提示 `/compact` 或新开会话；② **交付 E2E 前是压缩最佳时机**（离开 >5 分钟缓存失效）；③ **分段读取**——设计文档只读相关小节，进度文档按任务行编辑；④ **构建静音**——`mvn -q --no-transfer-progress`，失败只读 surefire 报告；⑤ **日志按关键行提取**（grep/tail，勿整读）；⑥ **大范围探索委派 Explore 子代理**，只回结论
8. **E2E 自测形态（定案）**：不启动服务；功能批完成即交付测试步骤 + 文档回写 + 提交，用户自测结果下轮回传更新 E2E 记录
9. **敏感词交付纪律（红线，全程强制）**：任何产出物（方案/代码/配置/词表/攻击语料/E2E 步骤）不得含字面攻击载荷——攻击内容仅以族系名/结构描述/样本 ID 表达；安全词表与攻击样本逐条编码存储（加载层解码，防后续会话读取触发上游注入检测致 400 block + 上下文污染）；配置键/指标名/类名不含攻击语义字面。全文见 `docs/project-optimization/安全加固专项优化方案（调研实证版）.md` §7
