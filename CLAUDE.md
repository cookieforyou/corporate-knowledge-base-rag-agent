# CLAUDE.md

## 项目概述

企业知识库 RAG Agent 工作台 & 知识库助手。基于 Spring AI 2.0 的企业级 RAG 平台，支持多格式文档解析、混合检索（向量+BM25+RRF）、带溯源的 Agent 对话、全链路可观测。

## 技术栈

- Java 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 GA
- PostgreSQL 18（主数据库 + pgvector 向量扩展）+ Milvus 2.6（可选分布式向量库）+ Elasticsearch 8.19（BM25）+ Redis 8（缓存）
- Maven 多模块（8 个子模块）

## 项目结构

```
kb-rag-agent/
├── kb-commons/        # 通用工具、DTO、异常、常量
├── kb-domain/         # JPA Entity、Repository、VO、枚举
├── kb-infrastructure/ # Milvus/ES/Redis/MinIO 适配
├── kb-etl/            # 文档 ETL 管道（独立可部署）
├── kb-ai-core/        # ChatClient、Advisor、Prompt、检索
├── kb-api/            # REST Controller、SSE（Spring Boot 入口）
├── kb-admin/          # 运维后台接口
└── kb-eval/           # AI 评估测试
```

## 构建与运行

```bash
# 编译
mvn clean compile

# 启动应用（kb-api 模块）
mvn spring-boot:run -pl kb-api

# 运行测试
mvn test
```

## 架构要点

- **Advisor 链**：所有自定义 Advisor 继承 `BaseAdvisor`，重写 `before()`/`after()`。Order 链：TokenBudget(10) → Audit(50) → RateLimit(100) → OutputGuardrail(110) → Auth(200) → InputSanitize(300) → MessageChatMemory(400) → PrefetchRAG(500) → ToolCalling(1000)
- **混合检索**：`HybridRetrievalService` 并行调用 Milvus + ES，RRF 融合排序
- **ETL 管道**：`SmartOcrRoutingReader` → `HtmlProtectingSplitter` → PG 持久化 → Milvus 向量化
- **数据架构**：PG 存主数据，Milvus 只存向量+chunk_id（双库解耦）

## 设计文档

完整设计文档：`docs/project-implement/企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告.md`
进度追踪：`docs/project-progress/项目阶段推进任务清单完成记录.md`

## 编码规范

- Java 21 特性优先：Record、Pattern Matching、Virtual Threads、`List.of()`/`Map.of()`
- Lombok：`@Data`、`@Slf4j`、`@Builder`
- API 统一响应：`ApiResponse<T>` record（code + message + data）
- 异常统一处理：继承 `BusinessException` 基类
- 日志：Lombok `@Slf4j`，关键路径用 `log.debug/Info`，异常用 `log.warn/error`
- 所有日期时间字段用 `LocalDateTime`
