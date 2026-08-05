# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台，目标能力：多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1 全部完成；**Phase 2 已收尾（2026-08-04 全量基线达标）**——检索簇 B+C、前端簇 D（2.13 ETL 进度 WebSocket / 2.14 检索调试台 / 2.15 Chunk 观测台+文档管理）与解析支线 2.1-2.3（DocMind 大模型版 + 保护式切分 + 页码下传）全部完成并经 E2E 验证；2.4（Contextual 增强+vision，设计即可选）延期，触发条件见进度文档；2.16 Golden 语料 74 条全量基线：Recall@5 0.971 / MRR 0.910 / Faithfulness 4.093 / Negative Rejection 1.00（含 5 条对抗性），全部可测验收项通过。E2E 清理 7 个真跑缺陷：ES 级联删除字段名、@EnableWebSocket 缺失、表格 HTML 未保护（OutputHtmlTable/llmResult）、page_num 缺失、embedding 单批超 20 条、删除幂等、rerank 契约误用旧格式静默降级。**Phase 3 进行中（已完成 11 项）**：3.1 多轮记忆（E2E 定案）、3.9+3.10 fail-closed 租户隔离、3.5/3.6 输入输出护栏、3.7/3.8 配额护栏（限流+Token 预算）、3.2 SmartRouting 主备熔断切换（均经 E2E 回归，3.5-3.10 用户验证通过）、3.3/3.4 工具链（Mock 工具层 + HITL 审批沙箱）、**3.19 RAG/Tool 双链路拆分 + kb-ai-agent 模块独立**（用户发起的架构改进）；任务清单复审完成；护栏加固路线图已立项不排期（12.4 S1-S9）。设计唯一依据见 `docs/project-implement/README.md`（v2 拆分修订版 + v2.1-v2.9 实现期修正），进度追踪见 `docs/project-progress/项目阶段推进任务清单完成记录.md`。

## 技术栈

- Java 21（虚拟线程，父 POM 启用 `--enable-preview`）+ Spring Boot 4.1.0 + Spring AI 2.0.0 GA + Maven 4
- LLM: DeepSeek V4 (`deepseek-v4-flash`) · Embedding: 阿里云百炼 DashScope (`qwen3.7-text-embedding`，OpenAI 兼容 API) · Rerank: `qwen3-rerank`（百炼专属 MaaS 工作空间端点）· Judge: `qwen3.7-plus`（跨厂商评判）
- 向量库: pgvector (PG 扩展) / Milvus 2.6（`kb.vector-store.provider` 配置切换，默认 milvus）
- PostgreSQL 18 + Elasticsearch 9.4.2 + Redis 8 + MinIO
- 认证: OAuth2 Resource Server (JWT) · 接入 Casdoor（前端 PKCE 流程）
- 前端: Vue3 + TypeScript + Element Plus + Pinia + Vite 6
- Maven 多模块（8 个子模块）

> **版本说明**：文档中的基础设施版本（PG 18、ES 9.4.2、Milvus 2.6、Redis 8）指 ECS 服务器上的**服务端部署版本**；pom 中对应的是**客户端库版本**，二者独立管理（如 elasticsearch-java 客户端为 8.14.3），不属于不一致。

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # ApiResponse、BusinessException 体系、Constants（含 RRF_K、DEFAULT_TOP_K）
├── kb-domain/         # 8 JPA Entity + 8 Repository + 6 枚举 + schema.sql（8 业务表 + kb_embeddings）
├── kb-infrastructure/ # vectorstore/（pgvector+Milvus 双后端条件装配）、MinIO、elasticsearch/（kb_chunks 索引模型 + 幂等初始化 EsIndexInitializer）、parsing/（DocMind 大模型版 + qwen3.5-ocr 解析客户端）
├── kb-etl/            # 文档 ETL：MinIO → SmartParsingRouter（NATIVE/DEEP/OCR 三路由）→ HtmlProtectingSplitter（表格/图片保护）→ PG 落库 → VectorStore 向量化（10 条/批）→ ES 双写；ETL 进度 Redis 双通道（@Async 虚拟线程）
├── kb-ai-core/        # 模块化 RAG 核心（3.19 起纯 RAG，不含工具链）：retriever/（HybridDocumentRetriever 双路并行 + ElasticsearchDocumentRetriever + RrfFusion + RerankDocumentPostProcessor）、advisor/（护栏/配额/溯源）、routing/（SmartRoutingChatModel 主备熔断）、memory/、ragAgentChatClient + RagChatService
├── kb-ai-agent/       # AI Agent 事务模块（3.19 拆出，Agent 事务域容器）：tool/（EnterpriseMockTools + ToolApprovalService HITL 审批账本）、config/（toolAgentChatClient + ToolCallingAdvisor(1000)）、service/（ToolChatService + toolContext 通道）；未来真实 OA/ERP 工具客户端 / MCP（5.11）/ Multi-Agent（5.3）落此
├── kb-api/            # REST Controller + SSE 命名事件 + SecurityConfig + JwtUtils + GlobalExceptionHandler（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（空模块，待开发）
├── kb-eval/           # AI 评估：EvalRunner + 双探针（vector/hybrid A/B）+ Golden Dataset（12 条 DDD 语料 + 15 条负向）+ CI 门禁
├── frontend/          # Vue3 前端（Vite 6；Login + Chat 溯源对话 + Documents 文档管理 + Debug 检索调试台 + Chunks 观测台）
└── docs/              # 设计文档（project-implement/ 按章拆分，入口 README.md）+ 进度追踪
```

模块依赖：kb-commons ← kb-domain ← kb-infrastructure ← kb-etl / kb-ai-core ← kb-api；kb-ai-agent 依赖 kb-ai-core，kb-api 依赖 kb-ai-core + kb-ai-agent；kb-admin、kb-eval 依赖 kb-ai-core。

## 运行环境

- **基础设施托管于 ECS**：PG / Milvus / ES / Redis / MinIO 均部署在远程 ECS 服务器，**本地无需搭建**，后端通过启动环境变量注入连接信息。
- **API 端口 8090**：服务器 8080 已被其他服务占用，本项目启动变量配置 `SERVER_PORT=8090`；前端 `frontend/.env` 的 `BACKEND_URL=http://localhost:8090` 与之配套（Vite dev server 代理 `/api`，前端端口 5173）。
- 常用环境变量：`SERVER_PORT`、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`KB_VECTOR_STORE_PROVIDER`（pgvector|milvus）及 `KB_MILVUS_*` / `KB_PGVECTOR_*`、`MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET`、`ES_URIS`、`REDIS_HOST`、`JWT_ISSUER_URI`、`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`（默认值见 `application-infra.yml` / `application-ai.yml`）。
- 启动：后端 `mvn spring-boot:run -pl kb-api`；前端 `cd frontend && npm install && npm run dev`。

## 当前实现要点

**双链路架构（3.19，2026-08-05，用户发起）**：单链揉合三痛点实证（HITL 确认轮被检索上下文带偏/工具请求白耗检索+重排/RAG 请求平白带工具 schema）后拆分——`ragAgentChatClient`（kb-ai-core，链 30/100/110/300/400/450/500，零工具）+ `toolAgentChatClient`（kb-ai-agent，链 30/100/110/300/400/1000 + defaultTools，零检索）；共享 smartRoutingChatModel / agentChatMemory（同 sessionId 跨链历史互通）/ 护栏配额 Advisor / RetrievalContext（配额与审批的身份源，两链都传）；**请求体 `mode: rag|tool` 显式分流**（缺省 rag 兼容现状，非法值 400 INVALID_MODE，自动意图路由留 5.4）；SSE 按链精简（rag 只推 TRACE、tool 只推 TOOL_CALL）；toolContext 仅 ToolChatService 组装（RagChatService 签名物理消除 HITL 凭证）；kb-eval 零影响（注入独立 chatClient）；**kb-ai-agent 为 Agent 事务域容器**（未来真实 OA/ERP 客户端/MCP 5.11/Multi-Agent 5.3 落此）

**工具链与 HITL（3.3/3.4，2026-08-05，组件位于 kb-ai-agent）**：`EnterpriseMockTools` Mock 工具层（契约对齐真实 OA/ERP，后续逐个替换）：读工具 queryEmployee/queryLeaveBalance 自动执行 + 写工具 submitLeaveRequest HITL 三段式（首调挂起返回 PENDING_APPROVAL+approvalId → `POST /api/v1/tools/approvals/{id}/approve` 确认 → 二次对话请求体 `approvedToolCallId` 校验消费后执行 EXECUTED）；`ToolApprovalService` Redis 账本（`rag:tool-approval:{id}` RMap<String,String>，TTL 默认 10 分钟 + 一次性消费 + 创建绑定 tenant/user、approve/consume 校验防重放越权；Redis 故障 fail-closed 抛 APPROVAL_STORE_UNAVAILABLE 拒写）；确认态经 `.toolContext()` 通道（与 advisor 参数独立，ChatClient 断言无 null 值）；工具调用记录写回 RetrievalContext 投影 SSE TOOL_CALL 命名事件 + 同步响应 toolCalls 字段；**ToolCallingAdvisor 自建 advisorOrder(1000)**——自动注册 DEFAULT_ORDER 为链最外层致工具循环每轮穿越全部内层 Advisor（配额按迭代消耗/记忆检索重复），源码实证后定稿设计链序位；确认轮携带 approvedToolCallId 时注入 system 指令令写工具复调确定化（工具调用为模型自主决策，凭证仅 toolContext 可见）

**多模型路由（3.2，2026-08-05，实用形态）**：`SmartRoutingChatModel`（implements ChatModel）包装主模型（deepSeekChatModel）+ 备用 `fallbackChatModel`（qwen3.7-plus 百炼 OpenAI 兼容端点，跨厂商容灾，JudgeModelConfig 同款装配，凭据经 `rag.routing.fallback.api-key` 回落 DASHSCOPE_API_KEY）；熔断三态无锁原子：连续失败 `rag.routing.circuit.failure-threshold`(5) 次 → OPEN `open-seconds`(30s) 直发备用 → 窗口后 HALF_OPEN 试探（成功闭合/失败重开）；失败即切不丢请求（当次转发备用）；流式错误 onErrorResume 切备用流整段重发（部分 token 后中断重复为已知取舍）；chatClient/agentChatClient 统一注入 `smartRoutingChatModel` 替代主模型直注（kb-eval 链同获容灾）；`rag.routing.fallback.enabled=false` 单模型透传降级；复杂度三级路由移交 Phase 5.4

**检索与对话链路（Phase 2 簇 B+C，已 E2E 验证）**

- 对话主链路：`RetrievalConfig` 组装 `RetrievalAugmentationAdvisor`（order=500，替代 Phase 1 QuestionAnswerAdvisor）：`RewriteQueryTransformer` 默认开（`rag.retrieval.rewrite.enabled`）/ `MultiQueryExpander` 默认关 / `HybridDocumentRetriever` 双路 → `RrfFusion`(K=60) → `RerankDocumentPostProcessor`(qwen3-rerank **扁平契约**：query/documents/top_n 与 model 同层、results 响应顶层——嵌套 input/parameters 是 gte-rerank 旧格式会被 400 拒；故障降级 fusion_score 截断) → `ContextualQueryAugmenter` Grounding 模板（[ref-N] 标注 + `allowEmptyContext=false` 空证据拒绝模板）
- 双路检索：`HybridDocumentRetriever`（虚拟线程并行，单路 5s 超时降级）= 向量路（直连 VectorStore，similarityThreshold=0.5，recallSize=topK×2）+ `ElasticsearchDocumentRetriever`（ik BM25，tenant/is_deleted 过滤）
- **RetrievalContext 参数化传递**（2026-08-02 重构，重要架构事实）：每请求纯实例，Controller 请求线程创建并以 JwtUtils 填充 tenantId/userId → ChatClient advisor 参数（`RetrievalContext.CONTEXT_KEY`）→ RetrievalAugmentationAdvisor 复制进 Query.context（源码核验）→ 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末 Controller 直读同一实例推送 SSE TRACE。**不使用 @RequestScope**（MVC 异步请求在请求线程返回后标记请求完结，作用域代理在整个流式生命周期不可解析，实证致租户过滤静默失效 + SSE 尾帧崩溃）
- SSE 协议（2.12，兼容 Phase 1）：`/chat/stream` 返回 `Flux<ServerSentEvent>`（请求线程订阅）；TOKEN/ERROR/DONE 无名事件保持 Phase 1 线形（`{"token":...}` / `{"error":...}` / `[DONE]`），TRACE 为新增命名事件（流末推送 bm25/vector/final 三路溯源，final 下标与 [ref-N] 对齐）
- **租户隔离 fail-closed 两层防线（3.9+3.10 合并项，2026-08-05）**：① 入口身份完整性守卫——SecurityConfig 只保证已认证，JWT owner claim 仍可能缺失，AgentController/RetrievalDebugController 校验 tenantId 非空，缺失抛 `IDENTITY_INCOMPLETE` 拒绝；② `HybridDocumentRetriever` 防御纵深——有 ctx 无租户返回空结果、双路零触达（拒答模板承接）。kb-eval 无 ctx 保持无过滤评估语义。跨租户泄露集成用例归 3.18；RBAC（doc_id/dept_id）属 3.11
- **输入输出护栏（3.5/3.6，2026-08-05）**：`InputSanitizeAdvisor`(order 300，先于记忆防 PII 落库)——手机/身份证/邮箱正则掩码（边界断言防长数字串误匹配）+ 注入关键词拦截（`PROMPT_INJECTION` 拒绝，词表 `rag.guardrail.input.injection-keywords` 配置优先、留空回退内置默认）；`OutputGuardrailAdvisor`(order 110)——黑名单（`rag.guardrail.output.blacklist`）命中整段替换安全话术，**流式聚合后验**（默认 adviseStream 仅末块执行 after() 追不回已流出 token，覆写为缓冲全答判定，合规优先 TTFT）。PG 归档/日志在 Controller 入口同规则脱敏（PII 不绕过护栏落 kb_message）。均只挂 agentChatClient，评估链不涉及；L1 形态，升级路线见设计 12.1.1/12.2.1
- **配额护栏（3.7/3.8，2026-08-05）**：`TokenBudgetAdvisor`(order 30)——每租户日账本 `rag:token-budget:{tenant}:{日期}`（RAtomicLong + expireIfNotSet 2 日 TTL），before() 超额抛 `TokenBudgetExceededException`（kb-commons 独立公开类）、after() 回写 usage 消耗 + Micrometer `rag.token.total`/`rag.token.budget.rejected`（不带租户标签防基数膨胀）；`RateLimitAdvisor`(order 100)——Redisson RRateLimiter 每租户令牌桶（`rag:ratelimit:tenant:{id}`，OVERALL 多实例共享），进程对每租户首次触达 setRate 覆盖式写入（配置单一事实源）。**租户身份经 RetrievalContext 参数链**（草稿 AuthAdvisor 上下文写入前提不成立）；**Redis 故障 fail-open**（before 放行/after 丢弃计数——可用性管控非安全边界，不击穿问答）；配额码 RATE_LIMITED/TOKEN_BUDGET_EXCEEDED 统一 429（GlobalExceptionHandler 配额码集合，流式 SSE ERROR 承接）；**流式消耗暂不计账**（未开 stream_options.include_usage，已知限制）；均只挂 agentChatClient
- 评估：kb-eval 双探针共存（`eval.probe` = auto/vector/hybrid 做 A/B）；`chatClient` Bean 名不变，被测链路切换评估器零感知；Golden 语料 74 条（finance/k8s/product/cross/docmind 5 集 54 正向 + 20 负向含 5 对抗性）；提速三件套：用例级虚拟线程并行（`eval.concurrency` 默认 5）+ 检索-only 秒级快跑（`eval.retrieval-only`，免 Judge 免 DASHSCOPE key）+ Milvus 冷启动预热；报告双通道（stdout + `target/eval-report.txt`，CWD 相对路径——IDEA 运行时落项目根 target/）

**多轮记忆与 Agent 链路（Phase 3 任务 3.1，代码完成待 E2E，设计 v2.3）**

- **Bean 拆分**：生产对话走独立 `agentChatClient`（MessageChatMemoryAdvisor order=400 → RetrievalTraceAdvisor 450 → RetrievalAugmentationAdvisor 500）；评估共享 `chatClient` 保持纯 RAG 不动——记忆 Advisor 缺失 CONVERSATION_ID 是 Assert 硬断言（非静默跳过），挂共享 Bean 会击穿 kb-eval；Phase 2 基线持续有效
- **Redis 记忆**：2.0 GA 仓储为 Jedis 形态（starter `spring-ai-starter-model-chat-memory-repository-redis`）；自动配置 jedisClient 仅 host/port 无密码——`ChatMemoryRedisClientConfig` 覆盖之，host/port/**password**/database 统一取自 `spring.data.redis.*`（与 Redisson 单一来源，REDIS_PASSWORD 环境变量）；**RedisChatMemoryRepository 亦由该配置显式装配**（自动配置的条件让位检查含 ChatMemory 类型，用户记忆 Bean 会致其静默回退 InMemory——⑦ 号坑）；**REDIS_DB 必须为 0**（RediSearch 索引只能建在 db0，db=1 时 FT.CREATE 报 `Cannot create index on db != 0` 阻断启动——此 fail-fast 是设计保险丝）；`spring.ai.chat.memory.redis.*` 仅记忆专属配置（index/prefix/TTL/init-schema）；依赖 Redis JSON+Query Engine（当前 ECS 为 redis-stack-server 容器提供，挂持久化卷）；`FaultTolerantChatMemory` 装饰降级（读失败→空历史、写失败→丢弃，Redis 抖动不击穿问答）；窗口 maxMessages=20（`rag.chat.memory.max-messages`）
- **会话协议**：/chat、/chat/stream 请求体可选 `sessionId`（前端复用即多轮），同步响应回传；缺省后端生成一次性 ID（兼容 Phase 1）。CONVERSATION_ID 与 RetrievalContext 同款 advisor 参数链传递
- **PG 归档旁路**：`ChatSessionService`（kb-api，@Async 虚拟线程 sessionArchiveExecutor）对话完成后异步落 kb_session/kb_message（补齐 kb_feedback 外键与历史列表数据缺口），失败只丢归档不丢对话；kb-eval 侧 `initialize-schema: false` 覆盖，评估进程零 Redis 依赖

**解析支线（2.1-2.3，E2E 验证）**

- SmartParsingRouter 三路由：非 PDF→NATIVE(Tika)；`kb.parsing.deep-by-default` 或上传参数 `parseRoute`→DEEP(DocMind)；密度<50 字符/页→OCR(qwen3.5-ocr)；自动路由失败回落 NATIVE，显式路由失败如实上抛
- **DocMind 实证要点（v2.2）**：表格 HTML 须提交时开 `OutputHtmlTable`（须同开 `LlmEnhancement`），HTML 在版面块 `llmResult` 字段（``` 围栏包裹需剥离）；正文字段是 `markdownContent` 不是 `markdown`；layouts 按页分组下传 → `kb_chunk.page_num` 有值（NATIVE 路由 Tika 无页级信息为 null）
- HtmlProtectingSplitter：`<table>`→TABLE Chunk（`original_content` 存 HTML）、`<img>`→IMAGE、小表格(<30 字符)退化文本、无保护标签走快速路径 = Phase 1 零变化
- 向量化 10 条/批：DashScope embedding 单次请求 ≤20 条硬限制，VectorStore 内部 TokenCountBatchingStrategy 只按 token 预算分批不限条数

**基础设施（Phase 1）**

- 上传/ETL：`DocumentService`（白名单 PDF/DOCX/MD/TXT/HTML → MinIO → kb_document）；`DocumentEtlService`（Tika → TokenTextSplitter 800/200/**maxNumChunks=10000** → kb_chunk → 向量化 → `EsIndexWriter` 双写，INDEXING 阶段失败不阻断）
- 认证：`SecurityConfig`（/actuator/health|info permitAll，/api/** authenticated，其余 denyAll，无状态）；`JwtUtils` 映射 Casdoor claims：`sub→userId`、`name→username`、`owner→tenantId`
- 双向量库：`spring.ai.vectorstore.type=custom` 禁用原生 auto-config，`VectorStoreConfig` 按 `kb.vector-store.provider` 条件创建 PgVectorStore / MilvusVectorStore
- 配置拆分：`application.yml`（kb-api）经 `spring.config.import` 导入 `application-infra.yml`（kb-infrastructure）+ `application-ai.yml`（kb-ai-core）
- **Redis 连接信息单一来源**：`application-infra.yml` 的 `spring.data.redis.*`（REDIS_HOST/PORT/PASSWORD/DB 环境变量）被两处消费，**不可移除**——① Redisson V4 自动配置（RedissonConnectionFactory + StringRedisTemplate → ETL 进度双通道）；② 会话记忆 Jedis 客户端（ChatMemoryRedisClientConfig 覆盖 Bean，spring-ai 自动配置不支持密码）
- **多 ChatClient Bean 纪律（3.19 起）**：容器内已有 chatClient（评估）/ ragAgentChatClient / toolAgentChatClient 三 Bean，所有注入点必须显式 `@Qualifier`（裸类型注入歧义致启动失败，3.2 @Primary 教训）；自建 ChatClient 链新增 Advisor 时核对 order 与链序表（11.2）一致
- 测试：kb-ai-core 75 + kb-ai-agent 23 + kb-eval 12 + kb-etl 14 + kb-infrastructure 10 + kb-api 20 单测（kb-admin 尚无测试类）

## 注意事项

- Maven 4 reactor 自动解析父子关系，子模块使用 `<parent/>` 即可；**单模块构建需 `-am`**（兄弟模块不在本地仓库时单 `-pl` 解析失败）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 7.x 要求）
- 父 POM dependencyManagement 已预埋后续阶段依赖：elasticsearch-java 8.14.3、jsoup 1.18.1、redisson 4.6.1、testcontainers 1.20.1；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递的 4.38.0 以最短路径覆盖 spring-ai 5.0.0 → `.entity()` 结构化输出 NoClassDefFoundError: JacksonSchemaModule）
- pgvector 模式需先以 superuser 执行 `CREATE EXTENSION IF NOT EXISTS vector;`（服务器 PG 若已启用可跳过）
- Phase 2 检索架构为 Spring AI 2.0 模块化 RAG（`RetrievalAugmentationAdvisor` + 自研 `HybridDocumentRetriever`/`RrfFusion` + ES ik BM25 双路 + qwen3-rerank）；Milvus 原生混合检索经源码级核验后否决。决策全文见 `docs/project-implement/10-混合检索引擎.md` §10.0
- **Spring AI 2.0 API 实证坑**（设计稿已回写 v2.1 修正）：① `OpenAiChatModel` 的异步 client 不继承预建同步 client 凭证，baseUrl/apiKey 必须经 `OpenAiChatOptions` 传入；② `ContextualQueryAugmenter.allowEmptyContext=true` 语义是「空证据原样返回问题由模型自由作答」（与直觉相反），拒答需 `false` + `emptyContextPromptTemplate`；③ `TokenTextSplitter.maxNumChunks` 是切片**数**上限（官方默认 10000），触顶后尾部剩余并入单个超大块，长文档会超 embedding 单条输入上限（8192×0.9）致 ETL 失败；④ Spring AI Document metadata 禁 null 值，可空字段须条件写入；⑤ `ChatClientRequest`/`ChatClientResponse` 是 record，位于 `chat.client` 包（非 `advisor.api`）；⑥ `MessageChatMemoryAdvisor` 缺失 CONVERSATION_ID 参数是 Assert 硬断言直接抛错（非静默跳过），多 Bean 场景会话 ID 必须由 Controller 保证非空；2.0 GA Redis 会话仓储是 Jedis 形态（`RedisChatMemoryConfig`），v1/v2 文档的 RedisTemplate 构造器不存在；⑦ **自动配置条件让位陷阱（3.1 E2E 实锤）**：`RedisChatMemoryAutoConfiguration#redisChatMemory` 的 `@ConditionalOnMissingBean` 同时检查 {RedisChatMemoryRepository, **ChatMemory**, ChatMemoryRepository}——用户自定义 ChatMemory Bean（agentChatMemory）先于自动配置注册，Redis 仓储静默让位并回退 InMemoryChatMemoryRepository：多轮对话表面连贯（进程内记忆），Redis 零痕迹、重启失忆、全程无报错。修复：用户侧显式装配 RedisChatMemoryRepository Bean（`ChatMemoryRedisClientConfig`，防回归测试 `ChatMemoryRedisWiringTest`）；⑧ `Usage.getTotalTokens()` 返回 **Integer 可空**（非原始 long），token 计量须判空；流式 usage 需 `stream_options.include_usage` 开启才随末块下发，未开启时流式消耗不可计量（3.8 已知限制）；⑨ Redisson 4.x `getRateLimiter` 有 String/CommonOptions 双重载，Mockito `any()` 匹配歧义须用 `anyString()`；`RRateLimiter.setRate` 覆盖式写入定稿 `RateLimiterArgs.of(RateType, rate, interval)` 形态（4.6.1 E2E 实证）；⑩ Mockito 对已 stub 抛异常的方法重 stub 时 `when(mock.method())` 会真实触发原异常，须用 `doReturn(...).when(mock).method(...)`；`verify(...).stream(any())` 撞 ChatModel stream 变参重载歧义须 `any(Prompt.class)`；⑪ **多 ChatModel Bean 装配歧义（3.2 启动失败实证）**：Spring AI `ChatClientAutoConfiguration#chatClientBuilder` 按类型裸注入单一 ChatModel，引入 fallbackChatModel/smartRoutingChatModel 后三 Bean 歧义致启动失败——`smartRoutingChatModel` 标 `@Primary` 消解（显式 @Qualifier 注入点不受影响）。教训：装配类变更单测不启动容器捕获不到此类问题，须以真实启动验证；⑫ 2.0 GA `ChatModel.getDefaultOptions()` 已 Deprecated，自定义 ChatModel 只覆写 `getOptions()`；⑬ **跨厂商模型路由的 Prompt options 屏障（3.2 E2E 实锤）**：Prompt 携带主模型 options（DeepSeekChatOptions），`OpenAiChatModel.createRequest` 对其强转 `OpenAiChatOptions` + 非空断言——转发异构备用模型前必须以备用模型自身 options 重建 Prompt（`new Prompt(instructions, fallback.getOptions())`）；⑭ **qwen3.5/3.6/3.7 商业版默认开思考模式**（`enable_thinking=true`，官方文档实证）：每次调用先生成大量思维链致单调用 20-60s——OpenAI 兼容端点调用需经 `OpenAiChatOptions.extraBody(Map.of("enable_thinking", false))` 显式关闭（extraBody 经 createRequest 透传请求体顶层，源码核验）；⑮ 手工 `OpenAiChatModel.builder()` 装配的模型不继承自动配置的 ObservationRegistry——模型调用无观测、`ChatModelCompletionObservationHandler` 不打 Completion 日志，须显式 `.observationRegistry(...)`
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal：MVC 异步请求完结后作用域代理不可解析，且 Advisor taskExecutor/Reactor 线程不继承请求属性。ChatMemory 的 CONVERSATION_ID 等同理经 advisor 参数传递

## 开发工作流约定（用户定案）

文档是项目的 DNA，**功能实现/修 bug 完成并校验无误后，第一时间更新文档，再提交代码**，顺序不可颠倒、不可遗漏：

1. **设计回写**：实现期对设计草图的实证修正（失效 API、语义反转、契约差异等）回写 `docs/project-implement/` 对应章节（版本号递增 + 修订注记），与 v2.1-v2.4 先例同格式
2. **进度更新**：`docs/project-progress/项目阶段推进任务清单完成记录.md` 对应任务行的完成情况 + 顶部日期状态行
3. **CLAUDE.md 同步**：「当前实现要点」/「注意事项」中受影响的架构事实
4. **git 提交**：按改动功能点提交（一功能一提交，代码 + 文档同批或紧随其后），提交信息沿用既有风格（`feat/fix/docs/refactor(scope): 中文摘要` + 正文要点）
5. **落码约束**：写代码前先进行源码级核验（框架 API 形态、契约、默认行为），先核验再落码，避免凭感觉瞎写
6. **通盘思考优先**：按项目文档推进时不机械照搬——实现每个功能点前先审视设计合理性与架构可维护性，有更优方案（如 3.19 双链路拆分否掉了 3.14 单链全挂形态）先提出与用户讨论定案，再实现并回写设计文档
5. **落码约束**：写代码前先进行源码级核验（如遇不确定或知识盲区请 Web 搜索以最新官方文档为准），先核验再落码，避免凭感觉瞎写
