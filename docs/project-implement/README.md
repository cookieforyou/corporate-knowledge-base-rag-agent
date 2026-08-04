# 企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告（v2 拆分版）

> **项目定位**：面向企业复杂文档场景的高可用、可溯源、可运维的 RAG Agent 知识库工作台
>
> **v2 修订日期**：2026-07-31 · v1 合订本（2026-07-27）归档于 [`archive/`](./archive/)（git 历史可溯）
>
> **v2.1 实现期修正（2026-08-02）**：簇 B/C 实现与 E2E 验证中对 v2 草图的源码级/实证修正，已回写各章：
> 1. **RetrievalContext 请求作用域 → 参数化传递**（10.2.1 重写）：@RequestScope 代理在 MVC 异步请求完结后不可解析，流式路径租户过滤/trace 静默失效——改每请求实例经 advisor 参数→Query.context 传递
> 2. **RetrievalTraceAdvisor 重写**（11.1.1）：v2 草图跨模块引用 kb-api JwtUtils（依赖方向不可逆）+ 作用域填充失效——瘦身为纯上下文 Map 操作，身份填充移至 Controller 请求线程
> 3. **ContextualQueryAugmenter 空证据语义**（10.6）：`allowEmptyContext=true` = 模型自由作答（与注释直觉相反），拒答需 `false` + `emptyContextPromptTemplate`（实测 Negative Rejection 0.80→1.00）
> 4. **Grounding 模板补 `{query}`**（10.6）：augment 渲染传 query+context 双参，缺 {query} 丢用户问题
> 5. **SSE 流末溯源**（11.3）：Controller 捕获纯实例（非作用域代理），供应商容错降级不击穿流
>
> **v2.2 实现期修正（2026-08-03）**：解析支线 2.1-2.3 接入与 E2E 实证修正，已回写第九章：
> 1. **DocMind 表格 HTML 获取方式**（9.1）：需提交时开启 `OutputHtmlTable`（须同开 `LlmEnhancement`），HTML 存放于表格版面块 `llmResult` 字段（实测 ``` 围栏包裹）——草图假设的 `html` 键不存在
> 2. **正文字段名 `markdownContent`**（9.1）：草图 `markdown` 键不存在，静默回退 `text` 致 Markdown 结构全失
> 3. **页级输出与页码下传**（9.1/9.2）：layouts 按页分组为每页一个 Document，`page_number` 经切分器下传落库 `kb_chunk.page_num`；文本不跨页
> 4. **embedding 单批条数硬限制**（9.3）：DashScope 单次请求 ≤20 条，VectorStore 内部 token 分批不限条数，ETL 侧固定 10 条/批分批调用
>
> **v2.3 实现期修正（2026-08-04）**：3.1 多轮记忆落地对 v2 草图的 2.0 GA 实证修正，已回写第十一章：
> 1. **Redis 会话记忆仓储形态**（11.2）：2.0 GA 为 Jedis 形态（`RedisChatMemoryConfig`），starter 坐标 `spring-ai-starter-model-chat-memory-repository-redis`、前缀 `spring.ai.chat.memory.redis.*`——草图的 RedisTemplate 构造器不存在；自动配置 jedisClient 仅支持 host/port，项目以 `ChatMemoryRedisClientConfig` 覆盖之，连接信息（含 password/database）统一取自 `spring.data.redis.*` 与 Redisson 单一来源；依赖 Redis JSON+Query Engine（Redis 8 内置）
> 2. **Agent Bean 拆分定稿**（11.2）：记忆 Advisor 缺失 CONVERSATION_ID 为 Assert 硬断言，不可挂评估共享 `chatClient`——生产对话链独立 `agentChatClient`（记忆400/溯源450/检索500），评估继续度量纯 RAG，Phase 2 基线不受影响
> 3. **记忆容错与 PG 归档**（11.2）：FaultTolerantChatMemory 装饰降级（读失败→空历史、写失败→丢弃，Redis 抖动不击穿问答）+ kb_session/kb_message 异步归档旁路（补齐 kb_feedback 外键与历史会话列表的数据缺口）
> 4. **sessionId 会话协议**（11.2）：请求体可选 sessionId（前端复用即多轮），同步响应回传；缺省后端生成一次性 ID，兼容 Phase 1 单轮前端

本目录是设计唯一依据。v1 原为 3794 行单文件，v2 按章拆分为独立文档，并对检索架构、Spring AI API、评估体系做了基于源码级核验的修订。

## 目录导航

### 第一卷：项目全景蓝图（战略层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第一章 | [项目背景与市场定位](./01-项目背景与市场定位.md) | v1 原文 |
| 第二章 | [技术基座与 Spring AI 2.0 能力矩阵](./02-技术基座与SpringAI能力矩阵.md) | v2 修订（Boot 基线、vectorstore auto-config 机制、百炼接入注记） |

### 第二卷：功能需求全景图（需求层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第三章 | [功能全景与优先级矩阵](./03-功能全景与优先级矩阵.md) | v1 原文 |
| 第四章 | [子系统功能详细设计](./04-子系统功能详细设计.md) | v2 修订（检索子系统组件更新） |

### 第三卷：技术架构设计（架构层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第五章 | [总体架构设计](./05-总体架构设计.md) | v2 修订（Advisor 链、能力层组件更新） |
| 第六章 | [Maven 多模块工程结构](./06-Maven多模块工程结构.md) | v1 原文 |
| 第七章 | [数据架构设计](./07-数据架构设计.md) | v1 原文 |

### 第四卷：分阶段落地路线图（执行层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第八章 | [五阶段实施路线图](./08-五阶段实施路线图.md) | v2 修订（**Phase 2 任务清单重写**，评估最小集前移） |

### 第五卷：核心模块技术实现（实现层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第九章 | [知识入库 ETL 管道](./09-知识入库ETL管道.md) | v2 修订（解析路由升级、ES 双写、Contextual Retrieval 可选项） |
| 第十章 | [混合检索引擎](./10-混合检索引擎.md) | v2 **完全重写**（方案甲+：模块化 RAG 架构，含决策裁决记录） |
| 第十一章 | [Agent 对话链路](./11-Agent对话链路.md) | v2 修订（虚构 API 全部修正为真实 API）+ v2.3（3.1 记忆形态/Bean 拆分/会话协议） |
| 第十二章 | [安全护栏体系](./12-安全护栏体系.md) | v2 修订（API 修正 + 注入检测升级路线） |
| 第十三章 | [可观测性体系](./13-可观测性体系.md) | v2 修订（API 修正 + Langfuse LLM 原生可观测层） |
| 第十四章 | [知识库运维](./14-知识库运维.md) | v1 原文 |

### 第六卷：工程质量保障（质量层）

| 章 | 文档 | 修订状态 |
|---|---|---|
| 第十五章 | [测试策略](./15-测试策略.md) | v1 原文 |
| 第十六章 | [AI 评估体系](./16-AI评估体系.md) | v2 修订（指标集扩充、CI 门禁、**阶段前移至 Phase 2**） |
| 第十七章 | [部署与运维](./17-部署与运维.md) | v1 原文 |
| 第十八章 | [交付验收标准](./18-交付验收标准.md) | v1 原文 |

### 附录

| 文档 | 修订状态 |
|---|---|
| [附录 A-D](./19-附录.md)（依赖清单 / 配置模板 / API 清单 / 避坑指南） | v2 修订（新增反模式 21-23） |

## v2 修订摘要

### 核心决策：检索架构「方案甲+」（2026-07-31 源码级核验后裁决）

v1 设计为「ES BM25 + Milvus 向量 + 手搓并行管道 + 自研 RRF」。经对 Spring AI 2.0.0 GA 源码与 Milvus 2.6 能力的双路核验，裁决如下：

- ❌ **Milvus 原生单引擎方案否决**：`MilvusVectorStore` 源码锁死 4 字段 schema（无 sparse/BM25 Function）、单路 dense 搜索、零扩展点；Spring AI 全仓无 sparse embedding 抽象；且 Milvus jieba 中文分词存在已知质量问题（milvus-io/milvus#36743），对"中文专有名词命中"核心痛点是致命风险。12 个月后可重估。
- ✅ **采纳方案甲+**：保留 ES ik BM25（中文质量 + 已部署 + 高亮/DSL），但构建于 Spring AI 2.0 **模块化 RAG** 之上而非手搓：`RetrievalAugmentationAdvisor` + 自定义 `HybridDocumentRetriever`（双路虚拟线程并行 + `RrfFusion`）+ 框架内置查询改写/扩展 + `qwen3-rerank` 重排序 + `ContextualQueryAugmenter` 证据注入。详见[第十章](./10-混合检索引擎.md)。

### 其他修订

1. **虚构 API 清零**：v1 示例代码中 8 处 Spring AI API 不存在（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()`、`spring.ai.vectorstore.type=custom`、指标名 `.duration`、`response.response()`、MCP SSE 传输），已在第九~十三章及附录全部修正为 2.0.0 GA 真实 API。
2. **评估体系前移**：v1 将评估全部置于 Phase 5，与 Phase 2 验收标准（命中率 > 85%）自相矛盾。v2 将 kb-eval 最小集（Golden Dataset + Top-K 召回/MRR + Faithfulness）前移至 Phase 2，并扩充指标集（+Negative Rejection、Hallucination Rate、Noise Robustness、Citation Attribution）。详见[第十六章](./16-AI评估体系.md)。
3. **解析路由升级**：深度解析链路调整为 API 化解析——**阿里云文档智能 DocMind 大模型版**为主（ECS 2 核无 GPU 资源约束定案；Docling 同机部署经核验不可行：内存/速度双否决），qwen3.5-ocr 备选，云 OCR 兜底扫描件。详见[第九章](./09-知识入库ETL管道.md) 9.1 决策注记。
4. **可观测双层化**：Grafana（基础设施层）+ Langfuse（LLM 原生层，Spring AI 官方 OTel 集成）。详见[第十三章](./13-可观测性体系.md)。
5. **重排序选型**：BGE 本地/Cohere → DashScope qwen3-rerank API（与 Embedding 同生态、免 GPU）。

### 未修订部分

Phase 3-5 章节内容（第十二、十四章除外）保持 v1 原貌，待 Phase 2 收尾时按同样标准复审——远期设计不宜过早细化。路线图总体五阶段排期不变。

## 配套文档

- [项目阶段推进任务清单完成记录](../project-progress/项目阶段推进任务清单完成记录.md) — 进度追踪（Phase 1 已完成，Phase 2 任务清单已与 v2 对齐）
- 项目根 `CLAUDE.md` — 工程约定与当前实现要点
- v1 合订本：[archive/](./archive/) — 修订前完整原文
