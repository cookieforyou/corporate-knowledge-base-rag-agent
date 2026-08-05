# 第六章：Maven 多模块工程结构

> 本章为《企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告》v2 拆分版的一部分（原第 三 卷「技术架构设计（架构层）」）
>
> [📑 返回目录](./README.md) · 最后更新：2026-07-31 · 本章为 v1 原文迁移，内容未修订


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
