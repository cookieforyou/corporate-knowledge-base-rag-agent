# 第六章：Maven 多模块工程结构

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第 三 卷「技术架构设计（架构层）」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-09-12 · v2.10（新增 §6.3 全局常量收敛规约）· 此前：2026-07-31 v1 原文迁移 + v2.9（3.19：新增 kb-ai-agent 模块与依赖链，9 模块）


## 6.1 模块划分

```
kb-rag-agent/                           # 父工程
├── pom.xml                             # 父 POM（依赖管理 + BOM）
├── kb-commons/                         # 通用模块
│   └── src/main/java/com/enterprise/kb/commons/
│       ├── dto/                        # 通用 DTO（PageResult, ApiResponse）
│       ├── exception/                  # 业务异常体系
│       ├── constant/                   # 常量定义
│       └── util/                       # 工具类
├── kb-domain/                          # 领域模块
│   └── src/main/java/com/enterprise/kb/domain/
│       ├── model/                      # JPA Entity（KbDocument, KbChunk, ...）
│       ├── repository/                 # Spring Data JPA Repository
│       ├── vo/                         # VO 对象
│       └── enums/                      # 枚举（DocumentStatus, ChunkType, ParseRoute）
├── kb-infrastructure/                  # 基础设施模块
│   └── src/main/java/com/enterprise/kb/infrastructure/
│       ├── vectorstore/                # 向量库双后端配置（pgvector + Milvus 可切换）
│       ├── milvus/                     # MilvusServiceClient 配置
│       ├── elasticsearch/              # ElasticsearchClient 配置
│       ├── redis/                      # Redis 配置 + ChatMemory 实现
│       ├── minio/                      # MinIO OSS 适配
│       └── ocr/                        # 解析服务客户端（DocMind / qwen3.5-ocr 解析 API + 云 OCR 兜底，可插拔后端）
├── kb-etl/                             # ETL 管道模块（独立可部署）
│   └── src/main/java/com/enterprise/kb/etl/
│       ├── reader/                     # SmartParsingRouter 等
│       ├── transformer/                # HtmlProtectingSplitter、ContextualEnrichmentTransformer
│       ├── writer/                     # EsIndexWriter（ES 双写）；向量写入经 VectorStore
│       ├── pipeline/                   # EtlProgress / EtlStage
│       └── service/                    # DocumentEtlService
├── kb-ai-core/                         # AI 核心模块（纯 RAG 核心，3.19 起不含工具链）
│   └── src/main/java/com/enterprise/kb/ai/
│       ├── config/                     # ragAgentChatClient / RetrievalConfig / SmartRoutingConfig
│       ├── advisor/                    # 护栏/配额/溯源 Advisor
│       ├── routing/                    # SmartRoutingChatModel（主备熔断）
│       ├── memory/                     # Redis 记忆装配 + FaultTolerantChatMemory
│       ├── prompt/                     # PromptTemplateManager
│       ├── retriever/                  # HybridDocumentRetriever, ElasticsearchDocumentRetriever, RrfFusion, RerankDocumentPostProcessor
│       ├── service/                    # RagChatService（纯检索问答）
│       └── metrics/                    # AiBusinessMetrics
├── kb-ai-agent/                        # AI Agent 事务模块（3.19 拆出，Agent 事务域容器）
│   └── src/main/java/com/enterprise/kb/ai/agent/
│       ├── config/                     # toolAgentChatClient + ToolCallingAdvisor(1000)
│       ├── tool/                       # @Tool 工具层 + HITL 审批账本（EnterpriseMockTools / ToolApprovalService）
│       └── service/                    # ToolChatService（工具事务问答 + toolContext 通道）
├── kb-api/                             # 对外 API 模块
│   └── src/main/java/com/enterprise/kb/api/
│       ├── controller/                 # REST Controller
│       │   ├── AgentController         # SSE 流式对话
│       │   ├── DocumentController      # 文档管理
│       │   ├── KnowledgeController     # 知识检索
│       │   └── SessionController       # 会话管理
│       ├── dto/                        # API 专用 DTO
│       └── config/                     # Web 配置（CORS, SSE 超时）
├── kb-admin/                           # 运维后台模块
│   └── src/main/java/com/enterprise/kb/admin/
│       ├── controller/                 # Admin Controller
│       │   ├── ChunkAdminController    # Chunk CRUD + 索引重建
│       │   ├── AuditAdminController    # 审计日志查询
│       │   └── PromptAdminController   # Prompt 版本管理
│       └── dto/
└── kb-eval/                            # AI 评估模块
    └── src/main/java/com/enterprise/kb/eval/
        ├── dataset/                    # Golden Dataset 加载
        ├── metric/                     # ContextRelevance, Faithfulness 等指标
        └── runner/                     # 评估执行器
```

## 6.2 模块依赖关系

```
kb-commons            ← 无依赖（基础层）
    ↑
kb-domain             ← 依赖 kb-commons
    ↑
kb-infrastructure     ← 依赖 kb-domain（kb-commons 传递可得）
    ↑          ↑
kb-etl     kb-ai-core ← 依赖 kb-infrastructure（kb-domain + kb-commons 传递可得）
    ↑          ↑  ↑
    │          │  ├── kb-admin
    │          │  ├── kb-eval      ← 依赖 kb-ai-core（kb-domain + kb-infrastructure 传递可得）
    │          │  └── kb-ai-agent  ← 依赖 kb-ai-core（3.19：工具链/HITL/MCP 事务域）
    └─────┬────┘        ↑
          ↑             │
       kb-api         ← 依赖 kb-etl + kb-ai-core + kb-ai-agent
```

## 6.3 全局常量收敛规约（v2.10，2026-09-12）

**背景**：kb-commons `constant/Constants` 长期空置（仅两个分页常量），全仓协议性字面量以裸串散落或各类内同名常量重复定义——常量改动需多点同步，易漏改。经全仓扫描定案（2026-09-12 用户定案：字符串常量形态、四批推进），收敛至 `Constants` 嵌套分区。

**分区结构**（`com.enterprise.kb.commons.constant.Constants`）：

| 分区 | 内容 | 规模与说明 |
|---|---|---|
| `ErrorCodes` | 业务错误码 42 个（按域分组：身份/配额/护栏/文档 ETL/会话反馈/上传/编排 MCP/admin 运维/HITL/Golden） | 值即 API 契约（HTTP 响应 errorCode 字段 + 前端映射 + kb-eval 断言按字面消费），**只增不改**；批1 收敛 69 文件 223 处（BusinessException 抛点 / 异常类自带码 / 测试断言含 IT） |
| `Retrieval` | 检索路名（vector/bm25/graph）+ metadata 契约键（retrieval_source / fusion_score / rerank_score / bm25_score / graph_score / graph_entity_hits / doc_id / chunk_id / heading_path）+ 安全打标键（injection_hit / indirect_injection_hit）+ TRACE 终结名（final） | 写入侧（retriever / RrfFusion / Rerank / ETL）↔ 读出侧（Controller 溯源 / 审计 / MCP / 编排工具 / 调试台 / eval 探针）共享键面；批2 收敛 30 文件 180 处 |
| `ChatMode` | mode 三值 rag / tool / agent | AgentController 分流（类内常量桥接上收）+ 三 ChatService 审计参数 + admin 过滤值 |
| `SseEvent` | 命名事件 TRACE / TOOL_CALL / REPLACE / PROGRESS + PROGRESS 类型标签 stage | 无名 TOKEN/ERROR/DONE 帧不经事件名不列；**前端 TS 侧同值契约各自持有**（跨语言无法共享，改动须双侧同步） |
| `AuditStatus` | 审计三态 SUCCESS / REJECTED / ERROR | kb_audit_log.status / 指标 request.* 标签 / MCP 审计 / admin 与导出过滤 / eval 拒绝统计；批3 合计 23 文件 104 处 |

**收敛判断标准与边界**（防过度收敛，后续新增常量同理裁量）：

1. **收敛**：跨类/跨模块多处存在的协议性字面量（写入↔读出契约、API 契约值、协议帧名）；
2. **不收敛——有枚举归属的值域**：DocumentStatus / GraphStatus / AttackType / FeedbackRating 等 19 枚举即各自单一事实源（测试对枚举序列化值的断言字面保留）；
3. **不收敛——同字面不同契约**：PG 列名（@Column "doc_id"）、ES 文档字段名（@JsonProperty "chunk_id"）、Cypher 参数名（"vector"）属 schema/存储契约；RetrievalContext.ToolCall 的 STATUS_REJECTED（HITL 审批域）与审计三态同字面不同域；
4. **不收敛——单类私有语义**：Mock 测试语料（E1001 工号）、loadtest 协议桩前缀串、日志/描述/提示语、配置键（归 @ConfigurationProperties / @Value）；
5. **既有公开常量桥接**：外部有引用者的类内常量（HEADING_PATH_KEY / INJECTION_HIT_KEY / MODE_RAG / STATUS_SUCCESS 等）保留定义、值改引 Constants——引用点零波及；
6. **rank 键族单一来源**：`ROUTE_X + RANK_KEY_SUFFIX` 拼接形态（RrfFusion 动态循环写入与读出清单同构），不另立完整键常量防双源漂移。

**验收**：纯字面量→常量引用机械替换，零契约变化零行为变化；全仓 test-compile 零 ERROR + 全模块单测绿（-DskipITs；IT 编译面已覆盖，运行面随下轮 kb-eval 门禁）。
