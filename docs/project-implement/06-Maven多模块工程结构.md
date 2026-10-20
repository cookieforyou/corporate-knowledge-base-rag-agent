# 第六章：Maven 多模块工程结构

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第 三 卷「技术架构设计（架构层）」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-10-20 · v2.13（6.1/6.2 现状对齐：10 模块树 + kb-loadtest + 各模块实际包结构）· v2.12（§6.3 批9 收口：纯别名桥接常量退役 8 个 + rank 键裸串收敛——规则 5 生命周期化）· 此前：2026-09-13 v2.11（§6.3 批5-8 续收：新增 MessageRole/JwtClaims/McpTool/ChainOrder/Ws/BeanNames 六分区 + Retrieval 五键扩充 + kb-eval EvalConstants 模块本地）+ 2026-09-12 v2.10（新增 §6.3 全局常量收敛规约）+ 2026-07-31 v1 原文迁移 + v2.9（3.19：新增 kb-ai-agent 模块与依赖链，9 模块）


## 6.1 模块划分（2026-10 现状，10 个 Maven 模块）

```
kb-rag-agent/                           # 父工程（dependencyManagement + BOM + 插件）
├── pom.xml
├── kb-commons/                         # 通用层（无业务依赖）
│   └── com/enterprise/kb/commons/
│       ├── dto/                        # ApiResponse / PageResult
│       ├── exception/                  # BusinessException 体系
│       ├── constant/                   # Constants 嵌套分区
│       ├── security/pii/               # PiiRecognizer 注册表（七类）
│       ├── guardrail/                  # 词项模型 / 规则源 SPI / 热重载注册表
│       └── util/                       # TextSanitizer 等
├── kb-domain/                          # 领域层（JPA + schema 事实源）
│   └── com/enterprise/kb/domain/
│       ├── model/                      # 9 Entity（Document/Chunk/Session/Message/Audit/Feedback/Section/Prompt/GuardrailRule）
│       ├── repository/                 # 9 Repository
│       ├── enums/                      # 8 枚举（DocumentStatus/ChunkType/ParseRoute/GraphStatus/...）
│       ├── spec/                       # Specification 动态谓词
│       ├── guardrail/                  # DbGuardrailRulesSource / Seeder
│       └── vo/                         # 领域视图
├── kb-infrastructure/                  # 基础设施适配
│   └── com/enterprise/kb/infrastructure/
│       ├── vectorstore/                # pgvector / Milvus 条件装配
│       ├── milvus/ · elasticsearch/ · redis/ · minio/
│       ├── parsing/                    # DocMind / OCR 客户端
│       └── graph/                      # Neo4j Driver / Gateway / Schema
├── kb-etl/                             # 文档加工流水线
│   └── com/enterprise/kb/etl/
│       ├── reader/                     # SmartParsingRouter
│       ├── transformer/                # HtmlProtectingSplitter / Sanitizing / ContextualEnrichment
│       ├── writer/                     # EsIndexWriter
│       ├── pipeline/                   # EtlProgress / 蓝绿 CLEANUP
│       ├── pipeline/graph/             # 实体抽取 / 派发 / 监听 SPI
│       ├── prompt/                     # 解析链 PromptTemplates
│       └── service/                    # DocumentEtlService
├── kb-ai-core/                         # 纯 RAG 核心（无工具链）
│   └── com/enterprise/kb/ai/
│       ├── config/                     # ragAgentChatClient / RetrievalConfig / SmartRoutingConfig
│       ├── advisor/                    # 审计/配额/护栏/路由/门控/缓存/溯源
│       ├── routing/                    # SmartRoutingChatModel（主备熔断）
│       ├── cache/                      # SemanticCacheService + CacheCheckAdvisor
│       ├── retriever/                  # Hybrid/ES/Graph retriever + RrfFusion + Rerank
│       ├── memory/                     # Redis 记忆 + FaultTolerant
│       ├── guardrail/                  # L2 语义判定 / 间接注入
│       ├── prompt/                     # 对话链 PromptTemplates
│       ├── metrics/                    # AiBusinessMetrics
│       └── service/                    # RagChatService
├── kb-ai-agent/                        # Agent 事务域
│   └── com/enterprise/kb/ai/agent/
│       ├── config/                     # toolAgentChatClient + orchestratorChatClient + ToolCallingAdvisor
│       ├── tool/                       # Mock 读/写工具 + HITL 审批账本
│       ├── orchestration/              # TaskTool / SubAgentRegistry / KnowledgeSearchTools
│       ├── service/                    # ToolChatService / AgentOrchestratorService
│       ├── mcp/                        # MCP 三件套 + 身份守卫 + 限流/审计
│       ├── dingtalk/                   # Stream 群机器人
│       └── a2a/                        # A2A 治理域
├── kb-admin/                           # 运维后台（kb-api 聚合）
│   └── com/enterprise/kb/admin/
│       ├── controller/                 # Chunk / 重建 / Bad Case / 词表 / 图谱回填 / 反馈导出
│       ├── service/                    # ChunkOps / Reindex / BadCase / GuardrailRuleOps / FeedbackExport / GraphBackfill
│       └── gateway/                    # ReindexGateway 依赖倒置
├── kb-api/                             # 对外 API + 启动入口
│   └── com/enterprise/kb/api/
│       ├── controller/                 # Chat / Document / Retrieval / Session / Feedback / Stats / A2A / Card
│       ├── dto/ · security/ · observability/ · ws/ · exception/
│       └── KbRagAgentApplication
├── kb-eval/                            # AI 评估（非 Web，跑完即退）
│   └── com/enterprise/kb/eval/
│       ├── dataset/                    # Golden 加载 / 多跳 / 注入语料
│       ├── metric/                     # 检索指标 / JudgePrompts / 校准
│       ├── runner/                     # EvalRunner / 探针 / 草稿 / 导出 / 差异 / 图路 A-B
│       └── config/                     # EvalProperties / JudgeModel
└── kb-loadtest/                        # Gatling 压测（test 域，gatling:test 显式触发）
    └── com/enterprise/kb/loadtest/
        ├── simulation/                 # A 检索 / B 桩压 / C 真实 LLM / D SSE / E hub 图路
        └── stub/                       # StubChatServer（纯 JDK）
```

## 6.2 模块依赖关系

```
kb-commons                     ← 基础层（Spring Boot starter + JSON）
    ↑
kb-domain                      ← 依赖 kb-commons（JPA + Flyway + schema.sql）
    ↑
kb-infrastructure              ← 依赖 kb-domain（向量库/ES/Redis/MinIO/Neo4j/解析）
    ↑                 ↑
kb-etl           kb-ai-core    ← kb-etl / kb-ai-core 依赖 kb-infrastructure
    ↑                 ↑   ↑
    │                 │   ├── kb-ai-agent   ← 依赖 kb-ai-core（工具/编排/MCP/钉钉/A2A）
    │                 │   ├── kb-admin      ← 依赖 kb-etl + kb-ai-core（运维后台）
    │                 │   └── kb-eval       ← 依赖 kb-ai-core + kb-etl（评估/图路 IT）
    └────────┬────────┘
             ↑
          kb-api               ← 聚合 kb-etl + kb-ai-core + kb-ai-agent + kb-admin

kb-loadtest                    ← 独立 test 域（不依赖业务模块，HTTP/SSE 黑盒压测）
```

**依赖纪律**：kb-api 只做聚合，不反向被业务模块依赖；kb-admin 经 Controller 前缀 `/api/v1/admin/**` 由 kb-api 统一安全收口；kb-eval 不装配 Web/Redis/护栏重载，保持测量独立性。

## 6.3 全局常量收敛规约（v2.12，2026-10-11 批9 收口；v2.11 2026-09-13 批5-8 续收；v2.10 2026-09-12 初立四批）

**背景**：kb-commons `constant/Constants` 长期空置（仅两个分页常量），全仓协议性字面量以裸串散落或各类内同名常量重复定义——常量改动需多点同步，易漏改。经全仓扫描定案（2026-09-12 用户定案：字符串常量形态、四批推进），收敛至 `Constants` 嵌套分区。

**分区结构**（`com.enterprise.kb.commons.constant.Constants`）：

| 分区 | 内容 | 规模与说明 |
|---|---|---|
| `ErrorCodes` | 业务错误码 43 个（按域分组：身份/配额/护栏/文档 ETL/会话反馈/上传/编排 MCP/admin 运维/HITL/Golden） | 值即 API 契约（HTTP 响应 errorCode 字段 + 前端映射 + kb-eval 断言按字面消费），**只增不改**；批1 收敛 69 文件 223 处（BusinessException 抛点 / 异常类自带码 / 测试断言含 IT） |
| `Retrieval` | 检索路名（vector/bm25/graph）+ metadata 契约键（retrieval_source / fusion_score / rerank_score / bm25_score / graph_score / graph_entity_hits / doc_id / chunk_id / heading_path）+ 安全打标键（injection_hit / indirect_injection_hit）+ TRACE 终结名（final） | 写入侧（retriever / RrfFusion / Rerank / ETL）↔ 读出侧（Controller 溯源 / 审计 / MCP / 编排工具 / 调试台 / eval 探针）共享键面；批2 收敛 30 文件 180 处 |
| `ChatMode` | mode 三值 rag / tool / agent | AgentController 分流（类内常量桥接上收）+ 三 ChatService 审计参数 + admin 过滤值 |
| `SseEvent` | 命名事件 TRACE / TOOL_CALL / REPLACE / PROGRESS + PROGRESS 类型标签 stage | 无名 TOKEN/ERROR/DONE 帧不经事件名不列；**前端 TS 侧同值契约各自持有**（跨语言无法共享，改动须双侧同步） |
| `AuditStatus` | 审计三态 SUCCESS / REJECTED / ERROR | kb_audit_log.status / 指标 request.* 标签 / MCP 审计 / admin 与导出过滤 / eval 拒绝统计；批3 合计 23 文件 104 处 |
| `MessageRole` | 消息角色 USER / ASSISTANT | kb_message.role 落库值域（批6）：归档写 ↔ 记忆回填/历史投影/反馈导出读；小写 user/assistant 是 SFT/DPO 开放格式契约不在此列 |
| `JwtClaims` | Casdoor claims 键 sub / name / owner | 身份协议（批6）：JwtUtils/SecurityConfig/WS 握手/kb-admin 六 Controller/MCP 身份守卫跨 3 模块 13+ 处 |
| `McpTool` | MCP 工具名 search / get_document / ask | 注册↔审计↔指标契约（批6）：@McpTool name + recordMcpToolCall + AiBusinessMetrics 分流 |
| `ChainOrder` | Advisor 链序 13 值（10/30/100/110/300/320/400/420/440/450/460/500/1000） | 链序表 11.2 的代码伴生单源（批8）：11 个 getOrder + 3 处 Memory order(400) + 工具循环 advisorOrder(1000) |
| `Ws` | WS 进度协议键 docId | EtlProgress 帧字段 ↔ WS 订阅参数/帧解析（批8） |
| `BeanNames` | Bean 名 22 个（ChatModel/ChatClient/Executor/装配件四族） | 装配契约（批8）：定义点显式 `@Bean(name=…)` 引常量 + 消费点 @Qualifier/@Async 引常量，建立编译期单一来源（值=原方法名，零行为变化）；多 ChatClient 纪律的常量伴生 |
| 顶层 `DIGEST_SHA_256` | 摘要算法名 SHA-256 | 词表指纹/语义缓存键/Golden 数据集与快照锚定跨 4 模块 6 处（批8） |
| `Retrieval`（批5 扩充） | metadata 五键：file_name / page_num / chunk_type / tenant_id / is_deleted | vectorMetadata 单一来源写 ↔ 三检索路重组 ↔ 审计/溯源/MCP/编排/调试台/eval 探针读；tenant_id/is_deleted 承载租户隔离 fail-closed 语义 |
| kb-eval `EvalConstants`（模块本地） | noise 裁决 CONSISTENT/DRIFTED + 维度名 5 个 + 探针模式名 4 个 | **模块内跨类协议不入全局 Constants**（批7 定案）：报告维度顺序/观察带缺省单源（EvalProperties 与 CalibrationReadbackRunner 双源合一）；引用域裁决值仍以 CitationMetrics 为源 |

**收敛判断标准与边界**（防过度收敛，后续新增常量同理裁量）：

1. **收敛**：跨类/跨模块多处存在的协议性字面量（写入↔读出契约、API 契约值、协议帧名）；
2. **不收敛——有枚举归属的值域**：DocumentStatus / GraphStatus / AttackType / FeedbackRating 等 19 枚举即各自单一事实源（测试对枚举序列化值的断言字面保留）；
3. **不收敛——同字面不同契约**：PG 列名（@Column "doc_id"）、ES 文档字段名（@JsonProperty "chunk_id"，**含查询 DSL `field(...)` 与部分更新 doc 键**——批5 定案，与向量 metadata 键同字面但分属 ES schema/Document metadata Map 两契约，改动双侧同步核验）、Cypher 参数名（"vector"）属 schema/存储契约；RetrievalContext.ToolCall 的 STATUS_REJECTED（HITL 审批域）与审计三态同字面不同域；
4. **不收敛——单类私有语义**：Mock 测试语料（E1001 工号）、loadtest 协议桩前缀串、日志/描述/提示语、配置键（归 @ConfigurationProperties / @Value）、模型名（@ConfigurationProperties 缺省值域，坑位㊸ 双源）、Micrometer 指标名（AiBusinessMetrics 类内聚即单点，测试 214 处断言字面保留——引用被测方常量会同错同盲）；
5. **既有公开常量桥接与退役（v2.12 批9 生命周期化）**：外部有引用者的类内常量（MODE_RAG / STATUS_SUCCESS / SIDE_INJECTION / ToolContextKeys 族等）保留定义、值改引单一来源（Constants 或域内公共常量）——引用点零波及；**但「值即单一来源常量、类内无自有语义」的纯别名桥接属过渡形态，全仓引用点迁移完毕后即退役**（不留空壳别名，防后人误当契约源）——批9 退役 8 个符号：`HEADING_PATH_KEY`（HtmlProtectingSplitter）、`INJECTION_HIT_KEY`（SanitizingTransformer / RrfFusion / IndirectInjectionRunner 三处）、`INDIRECT_HIT_KEY`（IndirectInjectionScanPostProcessor / IndirectInjectionRunner 两处）、`SOURCE_FINAL` 与 `META_DOC_ID`（CacheCheckAdvisor，原 private 别名），消费点直引 `Constants.Retrieval`；**退役判据双条**：①全仓零残留引用（java 主/测 + 文档 + 前端 + 工具脚本扫描，跨模块间接依赖随之消除——kb-admin/kb-eval 不再为取键而 import kb-etl 转换器类）；②契约锁定测试仍在**消费侧以字面独立持值**（VectorMetadataContractTest 的 `heading_path`/`injection_hit`、GraphDocumentRetrieverTest 的 `"graph_rank"`、EsChunkDoc `@JsonProperty`），防常量引用同错同盲。批9 附带 rank 键裸串收敛：RrfFusion 动态拼接的 `"_rank"` → `RANK_KEY_SUFFIX`（规则 6 单源闭合）；批6 追加：`"kb.retrieval_context"` 双定义收敛（ToolContextKeys 值引 RetrievalContext.CONTEXT_KEY）、UNCLASSIFIED 兜底引 GuardrailFamily.UNCLASSIFIED.name()（枚举即源）；
6. **rank 键族单一来源**：`ROUTE_X + RANK_KEY_SUFFIX` 拼接形态（RrfFusion 动态循环写入与读出清单同构），不另立完整键常量防双源漂移；
7. **契约锁定测试保留字面**（批5 定案）：VectorMetadataContractTest 全字面断言——其职责即独立锁定契约值防常量漂移，与引用常量的常规测试相反相成；
8. **模块内协议落模块本地**（批7 定案）：kb-eval judge 裁决/维度名/探针名等模块内跨类字面量入模块本地常量类（EvalConstants），不稀释全局 Constants 语义；ETL 解析中转键（page_count/table_count/image_count/page_number/original_html）同例落 SmartParsingRouter/HtmlProtectingSplitter 类内 public 常量。

**验收**：纯字面量→常量引用机械替换，零契约变化零行为变化；全仓 test-compile 零 ERROR + 全模块单测绿（-DskipITs；IT 编译面已覆盖，运行面随下轮 kb-eval 门禁）。批5-8 续收（2026-09-13）合计约 80 文件 260+ 处（批5 metadata 五键 ~40 文件 / 批6 三分区+去重 ~35 文件 / 批7 kb-eval 本地 ~8 文件 / 批8 SHA-256+链序+Bean 名+WS+ETL 中转键 ~35 文件），同口径回归；Bean 名定义点显式 name 值=原方法名，装配行为零变化（@Primary/@ConditionalOnProperty 组合原样保留）。**批9 别名退役批（2026-10-11）**：16 源文件（5 模块 kb-ai-core / kb-etl / kb-admin / kb-eval / kb-infrastructure）纯引用替换——删除项全为「已指向 Constants 的纯别名」而非自有定义，故零值漂移零行为变化；收口扫描三读数：跨模块同值残留 0、同值常量重定义仅余登记在案的 RetrievalContext.ToolCall.STATUS_REJECTED（规则 3 例外）、裸串余项全落 schema/DSL 边界（PG @Column / ES @JsonProperty / 查询 DSL / 部分更新 doc 键）；全模块 1143 用例绿（与收敛前逐模块逐位一致，纯重构无用例数增减）。
