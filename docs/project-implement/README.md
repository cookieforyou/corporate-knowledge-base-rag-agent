# 企业知识库 RAG Agent 工作台：Spring AI 2.0 全景实现报告（v2 拆分版）

> **项目定位**：面向企业复杂文档场景的高可用、可溯源、可运维的 RAG Agent 知识库工作台
>
> **v2 修订日期**：2026-07-31 · v1 合订本（2026-07-27）归档于 [`archive/`](./archive/)（git 历史可溯）

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
| 第十一章 | [Agent 对话链路](./11-Agent对话链路.md) | v2 修订（虚构 API 全部修正为真实 API） |
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
- ✅ **采纳方案甲+**：保留 ES ik BM25（中文质量 + 已部署 + 高亮/DSL），但构建于 Spring AI 2.0 **模块化 RAG** 之上而非手搓：`RetrievalAugmentationAdvisor` + 自定义 `HybridDocumentRetriever`（双路虚拟线程并行 + `RrfFusion`）+ 框架内置查询改写/扩展 + `gte-rerank` 重排序 + `ContextualQueryAugmenter` 证据注入。详见[第十章](./10-混合检索引擎.md)。

### 其他修订

1. **虚构 API 清零**：v1 示例代码中 8 处 Spring AI API 不存在（`ChatClientRequest.from()`、`ToolContext.requestApproval()`、`RedisChatMemory`、`ToolRegistry.merge()`、`spring.ai.vectorstore.type=custom`、指标名 `.duration`、`response.response()`、MCP SSE 传输），已在第九~十三章及附录全部修正为 2.0.0 GA 真实 API。
2. **评估体系前移**：v1 将评估全部置于 Phase 5，与 Phase 2 验收标准（命中率 > 85%）自相矛盾。v2 将 kb-eval 最小集（Golden Dataset + Top-K 召回/MRR + Faithfulness）前移至 Phase 2，并扩充指标集（+Negative Rejection、Hallucination Rate、Noise Robustness、Citation Attribution）。详见[第十六章](./16-AI评估体系.md)。
3. **解析路由升级**：深度解析链路从云 OCR API 调整为 MinerU/Docling 解析服务（HTTP sidecar）为主、云 OCR 兜底。详见[第九章](./09-知识入库ETL管道.md)。
4. **可观测双层化**：Grafana（基础设施层）+ Langfuse（LLM 原生层，Spring AI 官方 OTel 集成）。详见[第十三章](./13-可观测性体系.md)。
5. **重排序选型**：BGE 本地/Cohere → DashScope gte-rerank API（与 Embedding 同生态、免 GPU）。

### 未修订部分

Phase 3-5 章节内容（第十二、十四章除外）保持 v1 原貌，待 Phase 2 收尾时按同样标准复审——远期设计不宜过早细化。路线图总体五阶段排期不变。

## 配套文档

- [项目阶段推进任务清单完成记录](../project-progress/项目阶段推进任务清单完成记录.md) — 进度追踪（Phase 1 已完成，Phase 2 任务清单已与 v2 对齐）
- 项目根 `CLAUDE.md` — 工程约定与当前实现要点
- v1 合订本：[archive/](./archive/) — 修订前完整原文
