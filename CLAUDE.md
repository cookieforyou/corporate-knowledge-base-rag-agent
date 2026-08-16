# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台：文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1-3 与优化冲刺完成；**Phase 4 七簇推进：簇①②③④⑤ 收官（簇⑤ MCP Server 三轮 E2E 收敛全绿），下一棒簇⑥ 生产加固与压测**；遗留口径 3.11/5.4 缓做、3.16 取消、12.4 不排期。设计依据 `docs/project-implement/README.md`；**过程细节与 E2E 在** `docs/project-progress/项目阶段推进任务清单完成记录.md`（按任务行定位，勿整读）。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 百炼 (`qwen3.7-text-embedding`，OpenAI 兼容) · Rerank: `qwen3-rerank` · Judge: `qwen3.7-plus`
- 向量库: pgvector / Milvus 2.6（`kb.vector-store.provider` 切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO（版本指 ECS 服务端，pom 客户端独立）
- 认证: OAuth2 Resource Server (JWT) · Casdoor（前端 PKCE）
- 前端: Vue3 + TS + Element Plus + Pinia + Vite 6

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse/BusinessException/Constants/TextSanitizer
├── kb-domain/         # 8 Entity + 8 Repository + 6 枚举 + schema.sql
├── kb-infrastructure/ # vectorstore/（双向量库条件装配）、MinIO、elasticsearch/、parsing/（DocMind+OCR）
├── kb-etl/            # MinIO→SmartParsingRouter(NATIVE/DEEP/OCR)→切分→PG→向量化→ES 双写
├── kb-ai-core/        # 纯 RAG（无工具链）：retriever/（双路+RRF+重排）、advisor/、routing/（主备熔断）、memory/、metrics/、ragAgentChatClient
├── kb-ai-agent/       # Agent 事务域：tool/（Mock 工具+HITL 账本）、config/（toolAgentChatClient）、service/、mcp/（4.10 三件套+身份守卫）；未来 Multi-Agent 落此
├── kb-api/            # Controller + SSE + SecurityConfig + JwtUtils（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（Chunk 运维与重建 + Bad Case 闭环，kb-api 聚合）
├── kb-eval/           # EvalRunner + 探针 + Golden Dataset(146) + CI 门禁
├── frontend/          # Vue3（Login/Chat 溯源对话/Documents/Debug 检索台/Chunks 观测台）
└── docs/              # project-implement/ 设计按章 + project-progress/ 进度
```

## 运行环境

- **基础设施托管于 ECS**（均远程，本地无需搭建）。
- **API 端口 8090**（8080 被占，`SERVER_PORT=8090`）；前端 `.env` BACKEND_URL 配套。
- 环境变量名与默认值见 infra/ai.yml。
- 启动：后端 fat jar 直起（坑位㉘）；前端 `npm run dev`。

## 当前实现要点

**双链路架构**：`ragAgentChatClient`（kb-ai-core，纯检索零工具）+ `toolAgentChatClient`（kb-ai-agent，纯工具零检索 + defaultTools）；请求体 `mode: rag|tool` 显式分流；共享 smartRoutingChatModel / agentChatMemory / 护栏配额 Advisor / RetrievalContext；toolContext 仅 ToolChatService 组装（RagChatService 签名物理消除 HITL 凭证）；链序见 11.2

**全链路审计**：`AuditTraceAdvisor`(order 10 最外层)挂双链，异步落 kb_audit_log（旁路容错）；捕获被拒请求，status 三态 SUCCESS/REJECTED(errorCode)/ERROR；query 脱敏落库、rewritten_query 经装饰器捕获；`rag.audit.enabled` 可关；kb-eval 不挂

**业务指标**：`metrics/AiBusinessMetrics` 注册中心——rag.feedback/retrieval/tool.call/token/routing/guardrail/request/rerank/chunk.*/badcase.* 计数（request.* 与审计三态同语义）；不带租户标签防基数膨胀

**观测地基（簇①）**：Observation → otel bridge → OTLP Langfuse；总开关 `management.tracing.export.otlp.enabled` 默认关；内容捕获 `RAG_OBSERVABILITY_LOG_CONTENT` 唯一定义在 kb-ai-core/application-ai.yml（import 源优先），内容自桥接 gen_ai.prompt/completion（坑位㉔㉕）；trace 合树：双链显式装配 registry + Controller contextWrite 桥接（坑位㉗）+ 检索双执行器传播包裹；残余：流式主生成 POST 独立 trace 留簇⑥

**面板与统计（簇②）**：Grafana 四面板 + 监控 compose 落 infra/（本地形态，ECS 归簇⑥）；统计 API GET /api/v1/stats/overview|documents/processing（租户守卫）；无指标支撑面板不设

**Chunk 运维与重建（簇③）**：kb-admin 首建，kb-api fat jar 聚合（禁反向依赖）；租户守卫 @AuthenticationPrincipal Jwt 直消费（owner claim，不复用 JwtUtils 防成环）。Chunk CRUD：编辑 = 同源消毒 → PG 同步 → 异步重嵌入（**统一 delete→add 两步**——Milvus add 非 upsert 实证，chunk ID/original_content 不变）+ ES 覆写；软删委派 C1；恢复经重嵌入；守卫 fail-closed（跨租户 → CHUNK_NOT_FOUND，处理中 → DOC_NOT_READY）。重建：ReindexGateway 倒置委派 reparse（终态 future 汇聚）——PG 事实源全量重解析（蓝绿既有）+ ES 孤儿清扫（向量孤儿留离线）；任务表 Redis 形态（v2.36：RebuildTaskStore 抽象——RAtomicLong 计数 + 租户域索引 20 条 FIFO + TTL 24h；create/find/list fail-closed REBUILD_STORE_UNAVAILABLE 503、在途更新不阻断）。统计 chunkTotal = kb_chunk 存活计数（排除软删）。前端操作面归簇④ v2.35 扩展（运维中心「Chunk 运维」Tab，后端零改动）

**Bad Case 运营闭环（簇④）**：kb-admin 四端点——审计查询 GET /admin/audit-logs（时间/用户/会话/反馈/状态/根因/标注态可选 + feedbackExpectedAnswer 联查预填）；根因标注 PUT /audit-logs/{id}/root-cause（四分类 RootCause 枚举，kb_audit_log 新列 root_cause + (tenant_id,created_at DESC) 索引，**ECS 先 ALTER**）；Golden 回灌 POST /badcase/reingest——**Git Ops 文件通道**：审计行转用例写 `rag.admin.golden.dir`/badcase-qa.json（默认 kb-eval 语料目录，id=bc-{auditLogId} upsert 幂等，成功联动反馈 resolved），回灌→commit→CI 复跑闭环；反馈处理态 PUT /feedback/{id}/resolved（message→session 租户链）。守卫：跨租户/不存在一律 AUDIT_LOG_NOT_FOUND（不泄露存在性）。前端 /admin 运维中心四 Tab（仪表盘消费簇② stats / **Chunk 运维：编辑/软删/恢复 + 重建任务轮询** / 日志查询详情抽屉 / 标注+回灌对话框，检索快照候选快填）

**MCP Server（簇⑤ 4.10）**：`spring-ai-starter-mcp-server-webmvc` 落 kb-api（Streamable HTTP `/mcp` RouterFunction，SecurityConfig authenticated）；`McpKnowledgeTools` 三件套落 kb-ai-agent——@McpTool 注解扫描器自动收编（required 须显式钉）：search（检索链直调）/get_document（租户守卫+软删过滤+max-chunks 截断）/ask（ragAgentChatClient 全链——护栏/配额/审计自动复用，每次调用独立 mcp- 前缀 36 字符会话——审计 session_id VARCHAR(36) 钉死）；`McpIdentityGuard` 请求线程 JWT 物化 RetrievalContext（owner 空白 IDENTITY_INCOMPLETE，`rag.mcp.scope.required` scope 治理 MCP_SCOPE_DENIED，直消费 principal 不复用 JwtUtils 防成环）；容器内无 ToolCallback/Provider Bean（defaultTools 内联）HITL 工具不漏进 MCP；rag.mcp.* 三计数

**意图路由**：`QueryRoutingAdvisor`(440) 双层分类（正则快路 / 分类+改写单次调用预写）→ skipRetrieval；`RetrievalGateAdvisor`(500) 组合式门控包裹 RAA——skip 旁路携记忆直答，fail-open 回落；`rag.routing.intent.enabled` 可关

**工具链与 HITL（kb-ai-agent）**：`EnterpriseMockTools` 契约对齐真实 OA/ERP；读工具自动执行、写工具 HITL 三段式（挂起审批 approvalId → approve 端点 → 二次对话带 `approvedToolCallId` 一次性消费）；Redis 账本 TTL 10 分钟 + 一次性消费 + tenant/user 绑定，Redis 故障 fail-closed；确认态经 `.toolContext()` 通道；**ToolCallingAdvisor 自建 order 1000**（自动注册落最外层穿越内层 Advisor）

**多模型路由**：`SmartRoutingChatModel`（@Primary）包装主模型 DeepSeek V4（SmartRoutingConfig 手工装配 + include_usage 流式计账；starter 经 `spring.ai.model.chat=none` 门控让位，勿回写）+ 备用 `fallbackChatModel`（qwen3.7-plus 百炼端点，凭据回落 DASHSCOPE_API_KEY）；熔断三态无锁原子（rag.routing.circuit）；失败即切，流式整段重发备用流；异构备用须重建 Prompt（坑位⑭）；`rag.routing.fallback.enabled=false` 单模型透传

**检索与对话链路**

- 主链路：`RetrievalAugmentationAdvisor`(500) = CompressionQueryTransformer（`rag.retrieval.rewrite.enabled` 默认开）→ `HybridDocumentRetriever` 双路并行（tenant/is_deleted 过滤，5s 超时降级）→ `RrfFusion`(K=60) → `RerankDocumentPostProcessor`（qwen3-rerank **扁平契约**，故障/超时降级 fusion_score 截断）→ `ContextualQueryAugmenter`（**编号化 documentFormatter** 锚定 [ref-N] + `allowEmptyContext=false` 空证据拒答）；参数收编 `rag.retrieval.*`（改参须配 kb-eval）；多查询扩展关
- **RetrievalContext 参数链（核心模式）**：每请求纯实例，Controller 创建并填 tenantId/userId → advisor 参数 `CONTEXT_KEY` → 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末直读推 TRACE
- SSE 协议：`/chat/stream` 无名 TOKEN/ERROR/DONE（DONE 为 JSON {messageId,traceId}）+ 命名 TRACE（三路溯源与 [ref-N] 对齐）/ TOOL_CALL（仅 tool 链）
- 前端对话窗：sessionId 多轮 + rag/tool 切换 + TOOL_CALL 审批卡片
- 租户隔离 fail-closed 两层：① 入口身份守卫（tenantId 缺失抛 `IDENTITY_INCOMPLETE`）；② 检索器有 ctx 无租户返回空双路零触达
- 护栏与配额：`InputSanitizeAdvisor`(300) 归一化检测（仅检测不回写）+PII 掩码+注入拦截（`PROMPT_INJECTION`）；`OutputGuardrailAdvisor`(110) 黑名单整段替换、**流式聚合后验**；`TokenBudgetAdvisor`(30) 租户日账本；`RateLimitAdvisor`(100) Redisson 每租户令牌桶；配额码 RATE_LIMITED/TOKEN_BUDGET_EXCEEDED 统一 429；**Redis 故障 fail-open（配额）/ fail-closed（审批账本）**
- **词表工程（簇① v2.41）**：词表结构化+字面退役——词项模型（id/family/lang/type/value/action/enabled，value 逐条 Base64 编码加载层解码）+`GuardrailRulesLoader` 双源合并（结构化文件∪CSV 兼容，外部缺失回落缺省文件）；内置基线编码态落 `guardrail/injection-rules.yml`/`output-rules.yml`（builtin-inj/out-*，字面源退役）；三消费方同源按 action 分流（BLOCK 拒/FLAG 放行）；攻击语料引用形态（base64+sha256 锚点，解码+指纹校验）；全产出物零字面载荷；见 §12.7
- **用户反馈闭环**：POST /api/v1/feedback（messageId+userId upsert 可改评；归属 fail-closed，跨域伪装 MESSAGE_NOT_FOUND）+ Bad Case 查询；audit_log.feedback 凭 trace_id 回填
- 多轮记忆：`agentChatMemory` 显式装配 RedisChatMemoryRepository（**REDIS_DB 必须 0**，坑位⑦）；`FaultTolerantChatMemory` 降级；窗口 20 条；PG 归档 `ChatSessionService` 异步旁路；历史会话：会话端点 + 过期续聊回填；kb-eval 零 Redis 依赖
- 评估（kb-eval）：探针 `eval.probe`=auto/vector/hybrid/chain——hybrid 直调检索器、chain 走全链（须配 `eval.chain-probe.tenant-id`）；Golden 150 条（含注入 48，门禁限 L1 子集；chunk ID 确定性锚点）

**解析支线**：SmartParsingRouter 三路由（非 PDF→NATIVE Tika / 默认或 `parseRoute`→DEEP DocMind / 密度<50 字符/页→OCR；自动失败回落 NATIVE，显式失败上抛）；DocMind：表格 HTML 在 `llmResult`、正文 `markdownContent`、按页 page_num；HtmlProtectingSplitter 保护 `<table>`/`<img>` + heading_path 落三存储面；**Contextual 语境增强默认开**；chunk 确定性 ID（文档名#序号#增强前原文）；向量化 10 条/批（DashScope ≤20）

**基础设施**

- 上传/ETL：`DocumentService`（PDF/DOCX/MD/TXT/HTML 白名单 → MinIO → kb_document）；`DocumentEtlService`（解析→切分→**SanitizingTransformer**（S4+PII 入库消毒：`injection_hit` 打标不阻断，MinIO 原件保留）→kb_chunk→向量化→ES 双写，INDEXING 失败不阻断）；**增量重入库**：reparse/replace + version 列 + REINDEXING 占用 + CLEANUP 蓝绿 diff 清理
- 认证：`SecurityConfig`（/actuator/health|info|prometheus|metrics/** permitAll，/api/** authenticated，其余 denyAll，无状态）；`JwtUtils` Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁原生 auto-config，按 `kb.vector-store.provider` 条件装配；**pgvector 钉 idType=TEXT**（默认 UUID 致 delete 静默失效）
- 配置：kb-api application.yml 经 `spring.config.import` 导入 infra + ai yml；**Redis 连接单一来源**：application-infra.yml `spring.data.redis.*` 被 Redisson 与会话记忆 Jedis 共消费，**不可移除**
- **多 ChatClient Bean 纪律**：chatClient（评估）/ragAgentChatClient/toolAgentChatClient/evalGuardrailChatClient，注入点必须显式 `@Qualifier`；新增 Advisor 核对 order 与链序表（11.2）一致
- 测试：全模块单测绿 + kb-eval 33 Testcontainers IT（`mvn verify -pl kb-eval -am`，Docker 必需，无则 -DskipITs）

## 注意事项

- Maven 4 **单模块构建需 `-am`**（兄弟模块不在本地仓库）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`；**ddl-auto=validate**：实体新增字段须先在 ECS 执行 ALTER，缺列启动即失败
- 父 POM dependencyManagement 预埋后续依赖；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递 4.38.0 覆盖 → `.entity()` NoClassDefFoundError）
- pgvector 需先以 superuser `CREATE EXTENSION IF NOT EXISTS vector;`
- Milvus 原生混合检索否决（10 §10.0）；检索为 Spring AI 2.0 模块化 RAG
- **实证坑**：① OpenAiChatModel 异步 client 不继承同步 client 凭证，baseUrl/apiKey 须经 OpenAiChatOptions；② allowEmptyContext=true 即空证据自由作答，拒答需 false+emptyContextPromptTemplate；③ maxNumChunks 是切片**数**上限，触顶尾部并入超大块；④ Document metadata 禁 null；⑤ ChatClientRequest/Response 是 record（chat.client 包）；⑥ MessageChatMemoryAdvisor 缺 CONVERSATION_ID 硬断言抛错；⑦ **自动配置让位陷阱**：用户 ChatMemory Bean 先注册时静默让位回退 InMemory，须显式装配 + 防回归测试；⑧ Usage.getTotalTokens() Integer 可空须判空；流式 usage 需 include_usage；⑨ RateLimiterArgs.of(RateType,rate,interval)；Redisson 重载歧义须 anyString()；⑩ Mockito 重 stub 已抛异常方法须 doReturn().when()；⑪ 多 ChatModel Bean 歧义——smartRoutingChatModel @Primary（装配变更须真实启动验证）；⑫ getDefaultOptions() Deprecated，自定义只覆写 getOptions()；⑬ **Boot 4.1 迁 Jackson 3**：注入 tools.jackson.databind.json.JsonMapper（旧命名空间无 Bean）；注解仍 com.fasterxml.jackson.annotation；⑭ **跨厂商路由 Prompt 屏障**：转发异构备用前须以备用自身 options 重建 Prompt；⑮ **qwen3.5/3.6/3.7 商业版默认开思考**（20-60s/调用）——须 extraBody enable_thinking=false；⑯ 手工 builder() 不继承自动装配 ObservationRegistry——须显式 .observationRegistry()；⑰ ContextualQueryAugmenter 默认格式器不编号——[ref-N] 须配编号化格式器；⑱ **surefire JVM 必须带 --enable-preview argLine**（父 POM 已配）；⑲ **Boot 4.1 同名 Bean 不让位**（抛 BeanDefinitionOverrideException）——starter 让位经其 @ConditionalOnProperty 门控；⑳ **Boot 4.1 OTLP tracing 前缀**：management.otlp.tracing.*→management.opentelemetry.tracing.export.otlp.*，移至 spring-boot-micrometer-tracing-opentelemetry；㉑ **Prometheus counter**：以 total 结尾不重复追加 _total——表达式按 /actuator/prometheus 实证名；㉒ spring-boot:run fork JVM 同需 --enable-preview（见㉘）；㉓ **Langfuse OTLP**：endpoint 带全路径 /api/public/otel/v1/traces（缺路径 404）+ Basic base64(pk:sk)，仅 HTTP；㉔ **spring.config.import 导入源优先于导入方**——共享配置单源落导入侧；㉕ **log-prompt/log-completion 只打日志不进 span**——内容须自桥接 gen_ai.prompt/completion；㉖ context-propagation 1.2.1 静态初始化自动装载 accessor（MVC 回写/订阅捕获有效）；㉗ **流式 trace 双坑（碎片化定案）**：builder(chatModel) 单参默认 NOOP registry——须 builder(chatModel, observationRegistry, null, null)；BaseAdvisor.adviseStream publishOn(boundedElastic) ThreadLocal 不跨线程——父观测经 Reactor Context micrometer.observation 键传递，Controller contextWrite 兜底；㉘ **spring-boot:run 静默跑兄弟模块已安装旧 jar**——改动须先 install 再起；fat jar 直起更稳；㉙ **@Query `(:p IS NULL OR ...)` 可选参数是 PG 预编译雷**——PgJDBC 前 5 次无名语句不报错（绑定期携类型），达 prepareThreshold 提升命名预编译后 `$ IS NULL` 无类型上下文报 could not determine data type of parameter；多选项过滤查询统一 Specification 动态谓词（kb-domain spec/AuditLogSpecs/FeedbackSpecs 形态，kb-eval AdminQueryIT ≥6 次执行回归）；㉚ **MCP Server Streamable 传输须显式钉 `spring.ai.mcp.server.protocol: STREAMABLE`**——spring-ai 2.0.0 源码核验：Streamable 条件 matchIfMissing=false 而 SSE 条件 matchIfMissing=true，缺省静默装配 SSE 端点（/sse）致 /mcp 无路由（metadata 标注默认 streamable 与实际装配行为不一致）
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal（异步完结后作用域代理不可解析、Reactor 线程不继承）；CONVERSATION_ID 同理

## 开发工作流约定（用户定案）

文档是项目的 DNA：**功能实现/修 bug 校验后先更新文档再提交代码**，不可颠倒遗漏：

1. **设计回写**：实证性设计修正回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记）
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行 + 顶部日期状态行
3. **CLAUDE.md 同步**：受影响的架构事实（只记架构事实，过程细节入进度文档，控制体积 ≤20KB）
4. **git 提交**：一功能一提交（代码 + 文档同批），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前源码级核验（API 形态/契约/默认行为），不确定搜索官方文档，先核验再落码
6. **通盘思考优先**：实现前先审视设计合理性与可维护性，有更优方案先与用户定案，再实现并回写设计
7. **Token 与会话纪律**：① **功能点即会话边界**——单功能闭环后主动提示 `/compact` 或新开会话；② **交付 E2E 前是压缩最佳时机**（离开 >5 分钟缓存失效；中空窗 ≤50 分钟靠 keepalive 保活）；③ **分段读取**——设计文档只读相关小节（Grep/offset-limit），进度文档按任务行编辑；④ **构建静音**——`mvn -q --no-transfer-progress`，失败只读 surefire 报告；⑤ **日志按关键行提取**（grep/tail，勿整读）；⑥ **大范围探索委派 Explore 子代理**，只回结论
8. **E2E 自测形态（定案）**：不启动服务；功能批完成即交付测试步骤 + 文档回写 + 提交，用户自测结果下轮回传更新 E2E 记录
9. **敏感词交付纪律（红线，全程强制）**：任何产出物（方案/代码/配置/词表/攻击语料/E2E 步骤）不得含字面攻击载荷——攻击内容仅以族系名/结构描述/样本 ID 表达；安全词表与攻击样本逐条编码存储（加载层解码，防后续会话读取触发上游注入检测致 400 block + 上下文污染）；配置键/指标名/类名不含攻击语义字面。全文见 `docs/project-optimization/安全加固专项优化方案（调研实证版）.md` §7
