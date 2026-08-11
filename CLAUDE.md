# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台，目标能力：多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1 完成；Phase 2 收尾（Golden 74 条基线全项达标）；**Phase 3 实质收尾（17 项，含 5.4 收窄版意图路由提前落地）**；遗留口径：3.11 缓做、3.16 取消（Casdoor 统一管理）、3.18/5.4 剩余缓做（Phase 4 立项前）、护栏加固立项不排期（12.4 S1-S9）；**当前进行中：Phase 4 前优化冲刺（六簇，进度见优化文档 §8.3）**。设计唯一依据 `docs/project-implement/README.md`（v2 + v2.1-v2.18 修正）；**过程细节与 E2E 记录全部在** `docs/project-progress/项目阶段推进任务清单完成记录.md`（按任务行定位，勿整读）。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API) · Rerank: `qwen3-rerank`（百炼专属 MaaS 工作空间端点）· Judge: `qwen3.7-plus`（跨厂商评判，enable-thinking 默认关）
- 向量库: pgvector (PG 扩展) / Milvus 2.6（`kb.vector-store.provider` 配置切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO
- 认证: OAuth2 Resource Server (JWT) · 接入 Casdoor（前端 PKCE 流程）
- 前端: Vue3 + TypeScript + Element Plus + Pinia + Vite 6
- Maven 多模块（9 个子模块）

> **版本说明**：PG/ES/Milvus/Redis 版本指 ECS 服务端；pom 客户端库版本独立管理。

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse、BusinessException、Constants（分页）、TextSanitizer 消毒共享组件（对话/ETL 同源）
├── kb-domain/         # 8 JPA Entity + 8 Repository + 6 枚举 + schema.sql（8 业务表 + kb_embeddings）
├── kb-infrastructure/ # vectorstore/（pgvector+Milvus 条件装配）、MinIO、elasticsearch/、parsing/（DocMind + qwen3.5-ocr 客户端）
├── kb-etl/            # ETL：MinIO → SmartParsingRouter（NATIVE/DEEP/OCR）→ HtmlProtectingSplitter → PG → 向量化 → ES 双写；进度 Redis 双通道
├── kb-ai-core/        # 纯 RAG 核心（3.19 起不含工具链）：retriever/（双路并行+RRF+重排）、advisor/（审计/护栏/配额/溯源）、routing/（主备熔断）、memory/、metrics/、ragAgentChatClient + RagChatService
├── kb-ai-agent/       # Agent 事务域容器（3.19 拆出）：tool/（Mock 工具 + HITL 审批账本）、config/（toolAgentChatClient + ToolCallingAdvisor(1000)）、service/（ToolChatService + toolContext）；未来真实 OA/ERP 客户端/MCP(5.11)/Multi-Agent(5.3) 落此
├── kb-api/            # REST Controller + SSE 命名事件 + SecurityConfig + JwtUtils + GlobalExceptionHandler（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（空模块，待开发）
├── kb-eval/           # AI 评估：EvalRunner + 探针组 + Golden Dataset（102 条，7 文档）+ CI 门禁
├── frontend/          # Vue3 前端（Login + Chat 溯源对话 + Documents + Debug 检索调试台 + Chunks 观测台）
└── docs/              # 设计文档（project-implement/ 按章拆分，入口 README.md）+ 进度追踪
```

模块依赖：kb-commons ← kb-domain ← kb-infrastructure ← kb-etl / kb-ai-core ← kb-api；kb-ai-agent 依赖 kb-ai-core，kb-api 依赖 kb-ai-core + kb-ai-agent；kb-admin、kb-eval 依赖 kb-ai-core。

## 运行环境

- **基础设施托管于 ECS**：PG / Milvus / ES / Redis / MinIO 均部署在远程 ECS 服务器，**本地无需搭建**。
- **API 端口 8090**：8080 被占，`SERVER_PORT=8090` 注入；前端 `frontend/.env` BACKEND_URL 配套（Vite 代理 `/api`）。
- 常用环境变量（默认值见 infra/ai.yml）：`DB_*`/`KB_VECTOR_STORE_PROVIDER`（pgvector|milvus）/`KB_MILVUS_*`/`KB_PGVECTOR_*`/`MINIO_*`/`ES_URIS`/`REDIS_HOST`/`JWT_ISSUER_URI`/`DEEPSEEK_API_KEY`/`DASHSCOPE_API_KEY`。
- 启动：后端 `mvn spring-boot:run -pl kb-api`；前端 `npm run dev`。

## 当前实现要点

> 本节只记约束未来实现的架构事实；各功能的过程细节、E2E 记录在进度文档对应任务行，设计详情见 project-implement/ 对应章节。

**双链路架构（3.19）**：`ragAgentChatClient`（kb-ai-core，纯检索零工具，链 10/30/100/110/300/400/440/450/500）+ `toolAgentChatClient`（kb-ai-agent，纯工具零检索，链 10/30/100/110/300/400/1000 + defaultTools）；请求体 `mode: rag|tool` 显式分流（缺省 rag，非法值 400，跨链自动路由留 5.4）；共享 smartRoutingChatModel / agentChatMemory（跨链互通）/ 护栏配额 Advisor / RetrievalContext；toolContext 仅 ToolChatService 组装（RagChatService 签名物理消除 HITL 凭证）；kb-eval 注入独立 chatClient 零影响

**全链路审计（3.12）**：`AuditTraceAdvisor`(order 10 最外层)挂双链，异步虚拟线程落 kb_audit_log（旁路容错：失败丢弃不击穿问答）；覆写 adviseCall/adviseStream 捕获被拒请求，status 三态 SUCCESS/REJECTED(errorCode)/ERROR；query_text 脱敏落库、rewritten_query 经 `RewriteCapturingQueryTransformer` 捕获；表 v2.10 四列 mode/status/error_code/tool_calls；kb-eval 不挂；`rag.audit.enabled` 可关

**业务指标（3.13）**：`metrics/AiBusinessMetrics` 统一注册中心——rag.feedback.*/rag.retrieval.*/rag.tool.call.*（ToolCall.status 分桶）/rag.token.*/rag.routing.* 计数；不带租户标签防基数膨胀；SecurityConfig 放行 prometheus/metrics/** 端点

**意图路由（5.4 收窄版）**：`QueryRoutingAdvisor`(440) 双层分类（正则快路 / 分类与改写合并单次调用，预写 rewrittenQuery）→ skipRetrieval；`RetrievalGateAdvisor`(500) 组合式门控包裹 RAA（框架类 final）——skip 旁路管线携记忆直答，fail-open 回落检索；`rag.routing.intent.enabled` 可关，闲聊免 TRACE 帧

**工具链与 HITL（3.3/3.4，kb-ai-agent）**：`EnterpriseMockTools` 契约对齐真实 OA/ERP（后续逐个替换）；读工具自动执行、写工具 HITL 三段式（首调挂起 PENDING_APPROVAL+approvalId → approve 端点 → 二次对话带 `approvedToolCallId` 消费后 EXECUTED）；`ToolApprovalService` Redis 账本（`rag:tool-approval:{id}`，TTL 10 分钟 + 一次性消费 + tenant/user 绑定防重放越权，Redis 故障 fail-closed 拒写）；确认态经 `.toolContext()` 通道（与 advisor 参数独立）；**ToolCallingAdvisor 自建 order 1000**（自动注册落链最外层，致工具循环每轮穿越全部内层 Advisor）

**多模型路由（3.2）**：`SmartRoutingChatModel`（@Primary）包装主模型（DeepSeek V4）+ 备用 `fallbackChatModel`（qwen3.7-plus 百炼端点，凭据回落 DASHSCOPE_API_KEY）；熔断三态无锁原子（连续失败 → OPEN 直发备用 → HALF_OPEN 试探，阈值 rag.routing.circuit 可配）；失败即切，流式 onErrorResume 切备用流整段重发；转发异构备用前须以备用自身 options 重建 Prompt（见注意事项⑭）；`rag.routing.fallback.enabled=false` 单模型透传；复杂度三级路由移交 5.4

**检索与对话链路（Phase 2 + Phase 3 护栏）**

- 主链路：`RetrievalAugmentationAdvisor`(500) = RewriteQueryTransformer（`rag.retrieval.rewrite.enabled` 默认开）→ `HybridDocumentRetriever` 双路并行（tenant/is_deleted 过滤，单路 5s 超时降级，参数见 rag.retrieval.*）→ `RrfFusion`(K=60) → `RerankDocumentPostProcessor`（qwen3-rerank **扁平契约**，故障降级 fusion_score 截断）→ `ContextualQueryAugmenter`（**编号化 documentFormatter** 锚定 [ref-N] + `allowEmptyContext=false` 空证据拒答模板）；调优参数收编 `rag.retrieval.*` 配置组（RetrievalProperties，默认=基线形态，改参须配 kb-eval 对比）；多查询扩展 `rag.retrieval.expansion.*` 默认关（A1 A/B：增益 MRR +0.025 不抵 TTFT 代价）；ETL 向量化批次 `kb.etl.embed-batch-size`
- **RetrievalContext 参数链（核心模式）**：每请求纯实例，Controller 请求线程创建并以 JwtUtils 填 tenantId/userId → advisor 参数 `CONTEXT_KEY` → 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末 Controller 直读同一实例推 SSE TRACE。**禁用 @RequestScope/ThreadLocal**（见注意事项末条）
- SSE 协议：`/chat/stream` 无名 TOKEN/ERROR/DONE（v2.14 起 DONE 为 JSON 载荷 {messageId,traceId}——3.17 反馈定位句柄）+ 命名 TRACE（三路溯源，final 与 [ref-N] 对齐；ChunkTrace 含 docId——「查看原文」通道）/ TOOL_CALL（仅 tool 链）
- 前端对话窗（3.15）：chat store 自备 sessionId 多轮 + rag/tool 切换；TOOL_CALL→审批卡片（approve 后自动确认轮）；[ref-N]/溯源条目经 docId 弹原文；marked+DOMPurify 渲染；历史会话栏
- 租户隔离 fail-closed 两层（3.9+3.10）：① 入口身份守卫（tenantId 缺失抛 `IDENTITY_INCOMPLETE`）；② HybridDocumentRetriever 有 ctx 无租户返回空结果双路零触达。kb-eval 无 ctx 不过滤；跨租户集成用例归 3.18；RBAC 属 3.11
- 护栏与配额（3.5-3.8，详见 12 章）：`InputSanitizeAdvisor`(300) 归一化检测视图（S1：NFKC+零宽+空白，仅检测不回写）+PII 掩码+注入拦截（`PROMPT_INJECTION`）；`OutputGuardrailAdvisor`(110) 黑名单整段替换、**流式聚合后验**；`TokenBudgetAdvisor`(30) 租户日账本 `rag:token-budget:{tenant}:{日期}`；`RateLimitAdvisor`(100) Redisson 每租户令牌桶；配额码 RATE_LIMITED/TOKEN_BUDGET_EXCEEDED 统一 429；**Redis 故障 fail-open（配额）/ fail-closed（审批账本）**；流式 token 消耗暂不计账（见注意事项⑧）
- **用户反馈闭环（3.17）**：POST /api/v1/feedback（messageId+userId upsert 可改评 + 期望回答/tags；归属经 message→session 校验 fail-closed，跨域伪装 MESSAGE_NOT_FOUND；归档竞态轮询兜底）+ GET Bad Case 查询（租户收敛、附原始问答）；kb_audit_log.feedback 凭 trace_id 回填 + audit_log_id 关联（旁路容错）；like/dislike 指标接线；前端 👍/👎 + 点踩期望回答表单
- 多轮记忆（3.1）：`agentChatMemory` 显式装配 RedisChatMemoryRepository（连接取自 `spring.data.redis.*`，**REDIS_DB 必须 0**，见注意事项⑦）；`FaultTolerantChatMemory` 降级；窗口 20 条（`rag.chat.memory.max-messages`）；PG 归档 `ChatSessionService` 异步旁路（失败只丢归档）；v2.17 历史会话：citations 归档 + 会话端点 + 过期会话续聊回填；kb-eval `initialize-schema: false` 零 Redis 依赖
- 评估（kb-eval）：探针 `eval.probe`=auto/vector/hybrid/chain——hybrid 直调检索器测本征质量（无改写/扩展/重排）；chain 走全 advisor 链经 RetrievalContext 取 final trace，度量前置组件收益（A1），须配 `eval.chain-probe.tenant-id`（有 ctx 无租户 fail-closed）；`chatClient` 独立注入；Golden 102 条（7 文档，chain 基线 0.897/0.876/0.842，hybrid 0.904/0.806/0.786，74 条旧基线作废）；报告 stdout + `target/eval-report.txt`

**解析支线（2.1-2.3，详见 9 章）**：SmartParsingRouter 三路由（非 PDF→NATIVE Tika / deep-by-default 或 `parseRoute`→DEEP DocMind / 密度<50 字符/页→OCR；自动路由失败回落 NATIVE，显式路由失败上抛）；DocMind 契约（详见 9 章 v2.2）：OutputHtmlTable+LlmEnhancement 同开、表格 HTML 在 `llmResult`、正文 `markdownContent`、按页 page_num；HtmlProtectingSplitter 保护 `<table>`/`<img>`；向量化 10 条/批（DashScope ≤20 条硬限制）

**基础设施（Phase 1）**

- 上传/ETL：`DocumentService`（PDF/DOCX/MD/TXT/HTML 白名单 → MinIO → kb_document）；`DocumentEtlService`（解析 → 切分 → **SanitizingTransformer**（S4+PII 入库消毒：chunk/向量/ES 存脱敏态、注入打标 `injection_hit` 入 metadata JSONB 不阻断、MinIO 原件保留）→ kb_chunk → 向量化 → ES 双写，INDEXING 失败不阻断）
- 认证：`SecurityConfig`（/actuator/health|info|prometheus|metrics/** permitAll，/api/** authenticated，其余 denyAll，无状态）；`JwtUtils` 映射 Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁用原生 auto-config，按 `kb.vector-store.provider` 条件装配 PgVectorStore / MilvusVectorStore
- 配置拆分：`application.yml`（kb-api）经 `spring.config.import` 导入 `application-infra.yml` + `application-ai.yml`
- **Redis 连接信息单一来源**：`application-infra.yml` 的 `spring.data.redis.*` 被 Redisson 自动配置与会话记忆 Jedis 共消费，**不可移除**
- **多 ChatClient Bean 纪律（3.19 起）**：chatClient（评估）/ ragAgentChatClient / toolAgentChatClient，所有注入点必须显式 `@Qualifier`（裸类型注入致启动失败）；新增 Advisor 核对 order 与链序表（11.2）一致
- 测试：全模块单测绿

## 注意事项

- Maven 4 reactor 自动解析父子关系，子模块使用 `<parent/>` 即可；**单模块构建需 `-am`**（兄弟模块不在本地仓库时单 `-pl` 解析失败）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 7.x 要求）；**ddl-auto=validate**：实体新增字段须先在 ECS 执行 ALTER（schema.sql 注释内附升级语句），缺列启动即失败
- 父 POM dependencyManagement 已预埋后续阶段依赖：elasticsearch-java 8.14.3、jsoup 1.18.1、redisson 4.6.1、testcontainers 1.20.1；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递的 4.38.0 以最短路径覆盖 spring-ai 5.0.0 → `.entity()` 结构化输出 NoClassDefFoundError: JacksonSchemaModule）
- pgvector 模式需先以 superuser 执行 `CREATE EXTENSION IF NOT EXISTS vector;`（服务器 PG 若已启用可跳过）
- Phase 2 检索架构为 Spring AI 2.0 模块化 RAG；Milvus 原生混合检索经源码级核验否决（决策见 10-混合检索引擎.md §10.0）
- **Spring AI 2.0 API 实证坑**（设计稿已回写 v2.1 修正）：① `OpenAiChatModel` 的异步 client 不继承预建同步 client 凭证，baseUrl/apiKey 必须经 `OpenAiChatOptions` 传入；② `ContextualQueryAugmenter.allowEmptyContext=true` 语义是「空证据原样返回问题由模型自由作答」（与直觉相反），拒答需 `false` + `emptyContextPromptTemplate`；③ `TokenTextSplitter.maxNumChunks` 是切片**数**上限，触顶后尾部并入超大块致 ETL 失败；④ Spring AI Document metadata 禁 null 值，可空字段须条件写入；⑤ `ChatClientRequest`/`ChatClientResponse` 是 record，位于 `chat.client` 包（非 `advisor.api`）；⑥ `MessageChatMemoryAdvisor` 缺失 CONVERSATION_ID 是 Assert 硬断言直接抛错，会话 ID 由 Controller 保证非空；⑦ **自动配置条件让位陷阱**：`RedisChatMemoryAutoConfiguration` 的 `@ConditionalOnMissingBean` 含 ChatMemory 类型检查——用户 ChatMemory Bean 先注册时 Redis 仓储静默让位回退 InMemory。修复 = 显式装配 RedisChatMemoryRepository + 防回归测试；⑧ `Usage.getTotalTokens()` 返回 **Integer 可空**，token 计量须判空；流式 usage 需 `stream_options.include_usage` 开启才随末块下发；⑨ Redisson 4.x `getRateLimiter` 双重载致 Mockito `any()` 歧义须用 `anyString()`；`setRate` 覆盖式，定稿 `RateLimiterArgs.of(RateType, rate, interval)` 形态；⑩ Mockito 重 stub 已抛异常方法时 `when(mock.method())` 真实触发原异常，须用 `doReturn(...).when(mock)`；⑪ **多 ChatModel Bean 装配歧义**：`ChatClientAutoConfiguration` 按类型裸注入，多 Bean 歧义致启动失败——`smartRoutingChatModel` 标 `@Primary` 消解（装配变更须真实启动验证）；⑫ 2.0 GA `ChatModel.getDefaultOptions()` 已 Deprecated，自定义 ChatModel 只覆写 `getOptions()`；⑬ **Spring Boot 4.1 已迁 Jackson 3（tools.jackson 命名空间）**：自动装配的 JSON mapper 是 `tools.jackson.databind.json.JsonMapper`，注入须用 JsonMapper 具体类（注 `com.fasterxml.jackson.databind.ObjectMapper` 无 Bean 启动失败）；注解包仍为 `com.fasterxml.jackson.annotation`（Jackson 3 保留旧命名空间，勿迁）；⑭ **跨厂商路由 Prompt options 屏障**：Prompt 携带主模型 options，`OpenAiChatModel.createRequest` 强转 `OpenAiChatOptions` + 非空断言——转发异构备用前须以备用自身 options 重建 Prompt（`new Prompt(instructions, fallback.getOptions())`）；⑮ **qwen3.5/3.6/3.7 商业版默认开思考模式**：单调用 20-60s——OpenAI 兼容端点须经 `OpenAiChatOptions.extraBody(Map.of("enable_thinking", false))` 显式关闭；⑯ 手工 `OpenAiChatModel.builder()` 装配的模型不继承自动配置的 ObservationRegistry——须显式 `.observationRegistry(...)`；⑰ **ContextualQueryAugmenter 默认格式器拼接不编号**——[ref-N] 契约须配编号化格式器（每条资料前缀 [ref-N] 行），否则模型猜编号（抄正文圈号/越界/错位，v2.15 修复）；⑱ **surefire 测试 JVM 必须带 `--enable-preview` argLine**（父 POM 已配）：javac 仅对实际用 preview 语法的 class 打 65535 标记，缺 argLine 类加载失败且增量假绿可掩盖
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal（异步请求完结后作用域代理不可解析、Reactor 线程不继承请求属性）；CONVERSATION_ID 同理

## 开发工作流约定（用户定案）

文档是项目的 DNA，**功能实现/修 bug 完成并校验无误后，第一时间更新文档，再提交代码**，顺序不可颠倒、不可遗漏：

1. **设计回写**：实现期对设计草图的实证修正（失效 API、语义反转、契约差异等）回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记），与 v2.1-v2.4 先例同格式
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行的完成情况 + 顶部日期状态行
3. **CLAUDE.md 同步**：「当前实现要点」/「注意事项」中受影响的架构事实（只记架构事实，过程细节入进度文档，控制本文件体积 ≤20KB）
4. **git 提交**：按改动功能点提交（一功能一提交，代码 + 文档同批或紧随其后），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前先进行源码级核验（框架 API 形态、契约、默认行为），如遇不确定或知识盲区请 Web 搜索以最新官方文档为准，先核验再落码，避免凭感觉瞎写
6. **通盘思考优先**：按项目文档推进时不机械照搬，实现每个功能点前先审视设计合理性与架构可维护性，有更优方案（如 3.19 双链路拆分否掉了 3.14 单链全挂形态）先提出与用户讨论定案，再实现并回写设计文档
7. **Token 与会话纪律**（2026-08-07 定案，实证见进度文档当日状态行）：① **功能点即会话边界**——单功能「代码+文档提交 + E2E 步骤交付」闭环后，主动提示用户 `/compact` 或新开会话续下一功能（git 与文档是事实源，压缩不丢关键信息）；② **交付 E2E 步骤前是压缩最佳时机**（用户离开 >5 分钟提示缓存即失效，离开时上下文越小返回重读越省）。缓存 TTL 不可配置（Claude Code 客户端无此项，百炼网关固定 5 分钟、命中滑动续期）；中空窗（≤50 分钟）由 cache-keepalive 插件心跳保活（盈亏平衡点 ~50 分钟），超 50 分钟不保活（必亏），靠压缩/新会话；③ **分段读取**——设计文档只读当前任务相关小节（Grep/offset-limit 定位，勿整章读），进度文档按任务行定位编辑；④ **构建静音**——`mvn -q --no-transfer-progress`，失败只读对应模块 surefire 报告；⑤ **日志按关键行提取**（grep/tail，勿整读 service.log）；⑥ **大范围探索委派 Explore 子代理**，只回结论不进主上下文
