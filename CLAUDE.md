# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台。基于 Spring AI 2.0 的企业级 RAG 平台，目标能力：多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

**当前阶段**：Phase 1 全部完成；**Phase 2 已收尾（2026-08-04 全量基线达标）**——检索簇 B+C、前端簇 D（2.13 ETL 进度 WebSocket / 2.14 检索调试台 / 2.15 Chunk 观测台+文档管理）与解析支线 2.1-2.3（DocMind 大模型版 + 保护式切分 + 页码下传）全部完成并经 E2E 验证；2.4（Contextual 增强+vision，设计即可选）延期，触发条件见进度文档；2.16 Golden 语料 74 条全量基线：Recall@5 0.971 / MRR 0.910 / Faithfulness 4.093 / Negative Rejection 1.00（含 5 条对抗性），全部可测验收项通过。E2E 清理 7 个真跑缺陷：ES 级联删除字段名、@EnableWebSocket 缺失、表格 HTML 未保护（OutputHtmlTable/llmResult）、page_num 缺失、embedding 单批超 20 条、删除幂等、rerank 契约误用旧格式静默降级。**Phase 3 已开工（2026-08-04）**：任务清单复审完成（3.10 租户隔离主体已在 Phase 2 落地→重写为 fail-closed 加固并与 3.9 合并；3.4 HITL 补 approvalId 三要素；3.2 需先定备用模型）；3.1 多轮记忆代码完成待 E2E。设计唯一依据见 `docs/project-implement/README.md`（v2 拆分修订版 + v2.1/v2.2/v2.3 实现期修正），进度追踪见 `docs/project-progress/项目阶段推进任务清单完成记录.md`。

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
├── kb-ai-core/        # 模块化 RAG：retriever/（HybridDocumentRetriever 双路并行 + ElasticsearchDocumentRetriever + RrfFusion + RerankDocumentPostProcessor）、advisor/（RetrievalTraceAdvisor）、config/（RetrievalAugmentationAdvisor 组装）、ChatService
├── kb-api/            # REST Controller + SSE 命名事件 + SecurityConfig + JwtUtils + GlobalExceptionHandler（启动入口 KbRagAgentApplication）
├── kb-admin/          # 运维后台（空模块，待开发）
├── kb-eval/           # AI 评估：EvalRunner + 双探针（vector/hybrid A/B）+ Golden Dataset（12 条 DDD 语料 + 15 条负向）+ CI 门禁
├── frontend/          # Vue3 前端（Vite 6；Login + Chat 溯源对话 + Documents 文档管理 + Debug 检索调试台 + Chunks 观测台）
└── docs/              # 设计文档（project-implement/ 按章拆分，入口 README.md）+ 进度追踪
```

模块依赖：kb-commons ← kb-domain ← kb-infrastructure ← kb-etl / kb-ai-core ← kb-api；kb-admin、kb-eval 依赖 kb-ai-core。

## 运行环境

- **基础设施托管于 ECS**：PG / Milvus / ES / Redis / MinIO 均部署在远程 ECS 服务器，**本地无需搭建**，后端通过启动环境变量注入连接信息。
- **API 端口 8090**：服务器 8080 已被其他服务占用，本项目启动变量配置 `SERVER_PORT=8090`；前端 `frontend/.env` 的 `BACKEND_URL=http://localhost:8090` 与之配套（Vite dev server 代理 `/api`，前端端口 5173）。
- 常用环境变量：`SERVER_PORT`、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`、`KB_VECTOR_STORE_PROVIDER`（pgvector|milvus）及 `KB_MILVUS_*` / `KB_PGVECTOR_*`、`MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET`、`ES_URIS`、`REDIS_HOST`、`JWT_ISSUER_URI`、`DEEPSEEK_API_KEY`、`DASHSCOPE_API_KEY`（默认值见 `application-infra.yml` / `application-ai.yml`）。
- 启动：后端 `mvn spring-boot:run -pl kb-api`；前端 `cd frontend && npm install && npm run dev`。

## 当前实现要点

**检索与对话链路（Phase 2 簇 B+C，已 E2E 验证）**

- 对话主链路：`RetrievalConfig` 组装 `RetrievalAugmentationAdvisor`（order=500，替代 Phase 1 QuestionAnswerAdvisor）：`RewriteQueryTransformer` 默认开（`rag.retrieval.rewrite.enabled`）/ `MultiQueryExpander` 默认关 / `HybridDocumentRetriever` 双路 → `RrfFusion`(K=60) → `RerankDocumentPostProcessor`(qwen3-rerank **扁平契约**：query/documents/top_n 与 model 同层、results 响应顶层——嵌套 input/parameters 是 gte-rerank 旧格式会被 400 拒；故障降级 fusion_score 截断) → `ContextualQueryAugmenter` Grounding 模板（[ref-N] 标注 + `allowEmptyContext=false` 空证据拒绝模板）
- 双路检索：`HybridDocumentRetriever`（虚拟线程并行，单路 5s 超时降级）= 向量路（直连 VectorStore，similarityThreshold=0.5，recallSize=topK×2）+ `ElasticsearchDocumentRetriever`（ik BM25，tenant/is_deleted 过滤）
- **RetrievalContext 参数化传递**（2026-08-02 重构，重要架构事实）：每请求纯实例，Controller 请求线程创建并以 JwtUtils 填充 tenantId/userId → ChatClient advisor 参数（`RetrievalContext.CONTEXT_KEY`）→ RetrievalAugmentationAdvisor 复制进 Query.context（源码核验）→ 检索器/重排器经 `RetrievalContext.from(query)` 消费 → 流末 Controller 直读同一实例推送 SSE TRACE。**不使用 @RequestScope**（MVC 异步请求在请求线程返回后标记请求完结，作用域代理在整个流式生命周期不可解析，实证致租户过滤静默失效 + SSE 尾帧崩溃）
- SSE 协议（2.12，兼容 Phase 1）：`/chat/stream` 返回 `Flux<ServerSentEvent>`（请求线程订阅）；TOKEN/ERROR/DONE 无名事件保持 Phase 1 线形（`{"token":...}` / `{"error":...}` / `[DONE]`），TRACE 为新增命名事件（流末推送 bm25/vector/final 三路溯源，final 下标与 [ref-N] 对齐）
- **租户隔离 fail-closed 两层防线（3.9+3.10 合并项，2026-08-05）**：① 入口身份完整性守卫——SecurityConfig 只保证已认证，JWT owner claim 仍可能缺失，AgentController/RetrievalDebugController 校验 tenantId 非空，缺失抛 `IDENTITY_INCOMPLETE` 拒绝；② `HybridDocumentRetriever` 防御纵深——有 ctx 无租户返回空结果、双路零触达（拒答模板承接）。kb-eval 无 ctx 保持无过滤评估语义。跨租户泄露集成用例归 3.18；RBAC（doc_id/dept_id）属 3.11
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
- 测试：kb-ai-core 31 + kb-eval 12 + kb-etl 14 + kb-infrastructure 10 + kb-api 10 单测（kb-admin 尚无测试类）

## 注意事项

- Maven 4 reactor 自动解析父子关系，子模块使用 `<parent/>` 即可；**单模块构建需 `-am`**（兄弟模块不在本地仓库时单 `-pl` 解析失败）
- JSONB 字段须加 `@JdbcTypeCode(SqlTypes.JSON)`（Hibernate 7.x 要求）
- 父 POM dependencyManagement 已预埋后续阶段依赖：elasticsearch-java 8.14.3、jsoup 1.18.1、redisson 4.6.1、testcontainers 1.20.1；**`jsonschema-module-jackson` 锁定 5.0.0**（openai-java 传递的 4.38.0 以最短路径覆盖 spring-ai 5.0.0 → `.entity()` 结构化输出 NoClassDefFoundError: JacksonSchemaModule）
- pgvector 模式需先以 superuser 执行 `CREATE EXTENSION IF NOT EXISTS vector;`（服务器 PG 若已启用可跳过）
- Phase 2 检索架构为 Spring AI 2.0 模块化 RAG（`RetrievalAugmentationAdvisor` + 自研 `HybridDocumentRetriever`/`RrfFusion` + ES ik BM25 双路 + qwen3-rerank）；Milvus 原生混合检索经源码级核验后否决。决策全文见 `docs/project-implement/10-混合检索引擎.md` §10.0
- **Spring AI 2.0 API 实证坑**（设计稿已回写 v2.1 修正）：① `OpenAiChatModel` 的异步 client 不继承预建同步 client 凭证，baseUrl/apiKey 必须经 `OpenAiChatOptions` 传入；② `ContextualQueryAugmenter.allowEmptyContext=true` 语义是「空证据原样返回问题由模型自由作答」（与直觉相反），拒答需 `false` + `emptyContextPromptTemplate`；③ `TokenTextSplitter.maxNumChunks` 是切片**数**上限（官方默认 10000），触顶后尾部剩余并入单个超大块，长文档会超 embedding 单条输入上限（8192×0.9）致 ETL 失败；④ Spring AI Document metadata 禁 null 值，可空字段须条件写入；⑤ `ChatClientRequest`/`ChatClientResponse` 是 record，位于 `chat.client` 包（非 `advisor.api`）；⑥ `MessageChatMemoryAdvisor` 缺失 CONVERSATION_ID 参数是 Assert 硬断言直接抛错（非静默跳过），多 Bean 场景会话 ID 必须由 Controller 保证非空；2.0 GA Redis 会话仓储是 Jedis 形态（`RedisChatMemoryConfig`），v1/v2 文档的 RedisTemplate 构造器不存在；⑦ **自动配置条件让位陷阱（3.1 E2E 实锤）**：`RedisChatMemoryAutoConfiguration#redisChatMemory` 的 `@ConditionalOnMissingBean` 同时检查 {RedisChatMemoryRepository, **ChatMemory**, ChatMemoryRepository}——用户自定义 ChatMemory Bean（agentChatMemory）先于自动配置注册，Redis 仓储静默让位并回退 InMemoryChatMemoryRepository：多轮对话表面连贯（进程内记忆），Redis 零痕迹、重启失忆、全程无报错。修复：用户侧显式装配 RedisChatMemoryRepository Bean（`ChatMemoryRedisClientConfig`，防回归测试 `ChatMemoryRedisWiringTest`）
- **请求状态传递只用参数链**（RetrievalContext 模式），不用 @RequestScope/ThreadLocal：MVC 异步请求完结后作用域代理不可解析，且 Advisor taskExecutor/Reactor 线程不继承请求属性。ChatMemory 的 CONVERSATION_ID 等同理经 advisor 参数传递
