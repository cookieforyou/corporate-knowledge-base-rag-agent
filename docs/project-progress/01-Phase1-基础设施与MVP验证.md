# Phase 1：基础设施与 MVP 验证（第 1-3 周）

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。

**目标**：跑通"文档上传 → 基础切分 → 向量入库 → 单路 RAG 问答"闭环

### 任务清单（11 项）

| #    | 任务 | 负责模块 | 工时估算 | 验收标准 | 完成情况 |
|------|------|---------|---------|---------|---------|
| 1.1  | 搭建 Maven 多模块工程骨架（8 个模块） | 工程基础 | 2d | `mvn clean compile` 通过 | ✅ 已完成 (2026-07-27) |
| 1.2  | 配置 Spring Boot 4.1 + Spring AI 2.0.0 GA BOM | kb-commons | 0.5d | 依赖解析无冲突 | ✅ 已完成 (2026-07-27) |
| 1.3  | 实现 PostgreSQL 核心表（DDL + JPA Entity） | kb-domain | 1.5d | 表创建 + Repository CRUD 验证 | ✅ 已完成 (2026-07-28) |
| 1.4  | 实现基础文档上传 API（MultipartFile → MinIO） | kb-api | 1d | Postman 上传成功 | ✅ 已完成 (2026-07-29) |
| 1.5  | 实现 TikaDocumentReader + TokenTextSplitter 基础 ETL | kb-etl | 2d | PDF/Docx 解析 + 切分验证 | ✅ 已完成 (2026-07-29)；2026-08-01 修复：maxNumChunks=5 误用作「切片大小」实为「切片数上限」，长文档尾部剩余并入超大尾块超 embedding 输入上限（8192×0.9）致 ETL 失败，改回官方默认 10000 并加切分分布回归测试 |
| 1.6  | 实现 EmbeddingModel 向量化 + VectorStore 写入 | kb-etl | 1d | 向量库中可查向量 | ✅ 已完成 (2026-07-29) |
| 1.7  | 配置 ChatClient + QuestionAnswerAdvisor 基础 RAG | kb-ai-core | 1d | 知识库问答返回正确 | ✅ 已完成 (2026-07-29) |
| 1.8  | 实现基础对话 REST API（同步 + 流式 SSE） | kb-api | 1.5d | curl SSE 流式输出 | ✅ 已完成 (2026-07-29) |
| 1.9  | 实现统一 API 响应格式 + 全局异常处理 | kb-api | 0.5d | 错误响应格式统一 | ✅ 已完成 (2026-07-29) |
| 1.10 | 搭建 Vue3 前端基础工程 + 对话界面 | 前端 | 2d | 可对话 + 流式渲染 | ✅ 已完成 (2026-07-29) |
| 1.11 | 配置基础日志（Logback JSON 格式） | 工程基础 | 0.5d | 结构化日志输出 | ✅ 已完成 (2026-07-29) |

### 交付物

- [x] 可运行的多模块 Maven 工程
- [x] 文档上传 API（PDF/Docx/MD/TXT/HTML）
- [x] 基础 ETL 管道（Tika 解析 + Token 切分 + pgvector/Milvus 入库）
- [x] 单路 RAG 问答 API（同步 + SSE 流式 + 检索日志）
- [x] Vue3 前端：Casdoor 登录 + 对话界面 + 文档上传
- [x] 数据库 DDL 脚本（8 表 + kb_embeddings）
- [x] OAuth2 JWT 认证（Casdoor）
- [x] 双向量库切换（pgvector / Milvus）
- [x] Logback JSON 结构化日志
- [ ] Postman Collection 基础接口

### Phase 1 验收标准

| 指标 | 目标值 |
|------|--------|
| 文档上传→入库可用 | PDF(电子版)/Docx/MD/TXT |
| 单文档（50页）解析入库时间 | < 3 分钟 |
| 基础 RAG 问答准确率（简单事实型） | > 70% |
| SSE 首 Token 延迟 | < 2s |

