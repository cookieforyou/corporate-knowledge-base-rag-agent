# Phase 5 复审与规划方案（调研实证版）

> **性质**：Phase 5 规划提案（复审第八章/07 卷原清单 + 2026-08-22 多路网络调研实证），经用户定案后回写第八章，不代改其余章节
> **基准文档**：[第八章 五阶段实施路线图](../project-implement/08-五阶段实施路线图.md) · [07 卷 Phase 5 任务清单](../project-progress/07-Phase5-高级特性与持续进化.md) · [Phase 4 复审与规划方案](./Phase%204%20复审与规划方案（调研实证版）.md)（体例先例）· 设计文档 v2.62
> **规划日期**：2026-08-22（Phase 4 全阶段收官当日）
> **信息源**：多路并行网络调研（2026 企业级 RAG 主流架构 / Spring AI 2.0 GA Agent 能力 / 语义缓存生产实践与商业托管 / GraphRAG 基建选型 / A2A 协议生态 / 百炼商业 API，来源清单见附录 A）；三路代码与文档勘察（既有规划全量 / 六阶段收官与遗留项 / 代码复用落点，结论见附录 B）

---

## 一、复审背景

Phase 4 全阶段收官（2026-08-22，v2.62）后，Phase 5 立项输入齐备。原清单（12 项，08 章/07 卷）成稿于 v1 设计期（2026-07 前），其间发生四类变化，需要逐条复审：

1. **项目自身演进**：原清单 3 项已提前完成——5.4 收窄版（2026-08-08，rag 链内意图路由免检索短路，v2.13）、5.7 Golden 集（现 237 = 干净 110 + 注入 127，超额达成）、5.11 MCP Server（Phase 4 4.10 提前，v2.60）；5.4 剩余两项（A 跨链 mode=auto / B 复杂度三级路由）已于 2026-08-09 定案缓做并登记触发条件；
2. **代码基座远超规划假设**：双链路（rag 纯检索/tool 纯工具）+ 8 Advisor 标准化链序 + SmartRoutingChatModel 主备熔断装饰器 + 观测三件套（Micrometer/OTLP/Langfuse）+ kb-eval CI 门禁 + Gatling 压测模块 + Prompt Git Ops——语义缓存/多 Agent/评估进化所需的复用落点全部就位（附录 B）；
3. **约束画像更新**：用户明确**可加购第二台 2 核 8G ECS**（不涉大算力的基础设施不再受单机约束），同时预算敏感——商业托管按「价值 × 确定性 ÷ 月费」逐项评估，全 SaaS LLM API 格局不变（DeepSeek V4 主 + 百炼 qwen 族备/嵌入/重排/评估）；
4. **行业格局变化**：Agentic RAG 成 2026 主导生产范式（hybrid-first 基线之上叠加），语义缓存成为 agentic 延迟/成本的核心缓解手段（命中率 30-68%、延迟降 40-50% 为业界常见读数），GraphRAG 维持「多跳专项增强非标配」定位但出现轻量基建选项，A2A 协议进入生产期（Linux Foundation 托管、150+ 组织、与 MCP 互补），Spring AI 2.0 GA 官方 Agentic Patterns 成型。

**复审方法**：原清单 12 项逐一给出「保留 / 调整 / 收窄 / 降级 / 销项 / 挂起」裁决（带调研依据）；验收标准逐条复核；新增候选项按（价值 × 确定性 ÷ 成本）排序；最后重组为六簇成簇推进计划（沿用优化冲刺与 Phase 4「2-3 关联点成簇」纪律）。

---

## 二、原清单 12 项逐条复审

| # | 原任务 | 裁决 | 依据与调整形态 |
|---|---|---|---|
| 5.1 | 部署 Neo4j + 知识图谱构建管道 | **调整（部署位置）** | 图数据库维持 Neo4j（2026 年 GraphRAG 生态事实标准：官方 Java 驱动 / Cypher 完整 / Spring Data Neo4j 成熟），**部署位置改为新购第二台 ECS**——Neo4j Community 内存基线 4G+，与业务全家桶（PG/ES/Redis/Milvus/MinIO/监控）同机争资源；独占新机 2C8G 可承载（企业级知识图谱 <100 万节点为极轻负载）。轻量备选已调研登记（FalkorDB Redis 基座 ~1/5 内存但 Java 生态弱；AuraDB Pro 托管 ~$65/GB/月预算敏感；Apache AGE 成熟度不足），均不选。**实体关系抽取走现有 qwen3.8-flash / DeepSeek API 结构化输出**，零本地算力，异步管道限流复用 ETL 进度模式 |
| 5.2 | GraphRAG 混合检索（Vector + Graph 双引擎） | **保留** | 与 5.1 合簇。融合形态 = 现有双路（向量+BM25）+ Graph 路经 **RRF 三路融合**（复用 `kb-ai-core/retriever/RrfFusion`，K=60 同口径）；`rag.graph.enabled` 降级开关，关即回落双路零回归；租户隔离沿用 RetrievalContext 参数链（图查询注入租户过滤，fail-closed 同现有两层纪律）。验收口径不变：多跳推理准确率 >80%（08 章 v2 注「2026 仍为专项增强非标配、索引成本 10-100x」判断经本轮调研复核仍成立——故抽取管道限流异步 + 仅对入库语料建图，不做全量重建常态化） |
| 5.3 | Multi-Agent Orchestrator（主+子 Agent） | **收窄** | **现状裁决前提**：工具层仍是 Mock（读×2 自动 + 写×1 HITL，无真实端点），原验收「任务完成率 >85%」无真实工具不可度量。2026 调研结论：企业 KB 主场景是**单 Agent 强溯源**，Multi-Agent 分解在工具集真实落地后才有价值拐点；Spring AI 2.0 GA 无原生 Agent 抽象，官方路径 = 组合式（Building Effective Agents 五模式 + Agentic Patterns 子代理编排）。**收窄形态**：Orchestrator-Workers 骨架（主 Agent + TaskTool 子代理委派）+ 3 个 Mock 子代理演示（知识检索/数据查询/报告生成）+ **真实工具挂接契约文档**；落 `kb-ai-agent`（模块 pom 已登记「Multi-Agent 5.3 落此」）；复用双 ChatClient/Advisor order/toolContext/SmartRoutingChatModel 装饰器模式。**不引** spring-ai-agent-utils（成熟度一般、与 Advisor 体系重叠）与 Spring AI Alibaba graph 编排（另一套框架栈，与现有基线不兼容）。真实工具立项后自动升级回原验收。工时 5d→3d |
| 5.4 | 意图识别 + 自适应路由 | **调整归档** | 收窄版已完成（2026-08-08，用户拍板当日 E2E 通过，07 卷留痕完整）。**剩余两项正面处理**：A 跨链 mode=auto 的价值前提 = 真实工具落地 + 跨链混合流量，当前工具仍 Mock、双链分工清晰（请求体 `mode: rag|tool` 显式分流），**无触发条件** → 设计稿归档（锚点 11 章 §11.4），本阶段不推进；B 复杂度三级路由依赖轻量模型档位引入（3.2 定案「双模型下形同虚设」），继续挂起，显式登记为不启动项。两项工时归零 |
| 5.5 | 多知识库动态路由 | **降级登记** | 2026 业界多 KB 路由的触发条件 = 多业务线/多语言/多模态切分；本项目单租户企业库场景无真实需求。**不做预迁移**（不预建空列空表），仅设计稿登记：触发条件 = 第二个真实知识库接入需求提出，届时按「领域独立 VectorStore + 路由选择」原设计复活。工时 3d→0 |
| 5.6 | Semantic Cache（Redis RediSearch + 语义相似度） | **保留（复用落点已勘察）** | 调研实证：语义缓存是 2026 agentic RAG 延迟/成本的标配缓解手段（业界命中率 30-68%、命中延迟 50-100ms vs 全链 1-3s）。实现路径：**现有 Redis 8 自建**（Redis 8 已内置 Query Engine，KNN 向量检索 + 余弦相似），零新增基建与月费；复用 `EmbeddingModel` Bean（qwen3.7-text-embedding，检索同源保证相似性口径一致）、Redisson 已装配、`AiBusinessMetrics` 已预留 `rag.retrieval.cache.hit` 指标位。插入点 = 新增 CacheCheckAdvisor，候选位序在护栏簇（320）之后、记忆/路由（400/440）之前——**记忆一致性是设计关键点**（命中短路不得跳过会话记忆写入与审计），具体 order 与短路语义落码前源码级核验并回写 11.2 链序表。失效策略 = TTL + 知识库变更按 document_id 关联失效。商业备选登记：阿里云 Tair 语义缓存网关（精确+语义双策略、全托管回写，命中率高 5-10 点，新增月费，待流量规模化再重估）。验收扩为双口径：命中率 >30% + 命中延迟 P95 降 >40%，基线对照 = 簇① LT1 压测数据 |
| 5.7 | 扩充 Golden Dataset 至 200+ | **销项** | ✅ 已达成：现 237 条（干净 110 + 注入 127），三分区门禁全绿（16 章）。登记为提前收官项 |
| 5.8 | 扩充 LLM-as-Judge 评估管道 | **保留 + 扩充** | 16 章 v2 已定案的四项 Phase 5 阈值指标（Answer Correctness / Citation Attribution / Noise Robustness / Hallucination）扩管道保留，Judge 跨厂商基座复用（qwen3.8-flash）；**扩充「人类校准集」子项**：与 Golden 正交的 50 例双标注（封顶控制计费），Cohen's κ ≥ 0.80 一致率，落实原验收「人类校准 85-90%」口径；顺带消化簇④遗留的 TABLE 分类 Faithfulness 观察项读数。工时 3d→4d |
| 5.9 | A/B 测试框架（Prompt 效果对比） | **调整（轻量化）** | Prompt Git Ops 已就绪（4.8，v2.61，11 条全覆盖）——无需再建模板管理类抽象。形态 = kb-eval Judge **双跑对比**（同 Golden 集、双 Prompt 版本、差异报表自动产出），与 5.8 扩管道共享 Judge 基座。工时 3d→2d |
| 5.10 | 反馈闭环导出管道 | **保留** | 对齐 16.6 既有设计：JSONL 双格式（SFT 单轮 + DPO chosen/rejected 偏好对），数据源 = kb_feedback + 审计凭 trace_id 关联。**只导出不绑定平台**：首选微调方法 KTO 不在百炼微调服务支持清单，百炼 SFT/DPO 通道登记为可选接续（门槛达标：SFT ≥100 例 / DPO ≥50 对）；自训练否决（无 GPU）。工时 2d 不变 |
| 5.11 | MCP Server 宿主 | **销项** | ✅ 已在 Phase 4 4.10 提前交付（v2.60）：spring-ai-starter-mcp-server-webmvc Streamable HTTP + 三件套 + 身份守卫 + 独立限流审计，标准 Client 兼容与隔离验证通过（§11.8）。登记为提前收官项 |
| 5.12 | 企业微信/钉钉集成适配 | **收窄** | 投入产出评估：企微/钉钉完整自建应用需公网回调、审批流与组织管理配套，单开发者承接成本高；2026 主流触达形态已转向轻量机器人。**收窄为钉钉群机器人最小形态**：Stream 模式长连接接收 @ 消息优先（无公网回调依赖，落码前源码级核验 SDK 形态），回调 `/chat` 复用既有对话链路（含护栏/审计/溯源），Markdown 卡片回复；Stream 模式不可用则回落 outgoing webhook 形态论证入档。企微完整集成推迟（触发 = 真实组织需求）。工时 3d→1d |

**复审小结**：12 项中保留 4 项（5.2/5.6/5.8/5.10，含扩充）、调整 4 项（5.1 部署位置、5.9 轻量化、5.12 收窄、5.4 归档）、收窄 1 项（5.3）、降级登记 1 项（5.5）、销项 2 项（5.7/5.11 已提前完成）。剩余工作量由 41 人日瘦身至约 24 人日，为新增项（§四）腾出空间。

---

## 三、验收标准复审（08 章 Phase 5 验收表 + 第十八章联动）

| 原标准 | 裁决 | 新口径 |
|---|---|---|
| GraphRAG 多跳推理准确率 > 80% | 保留 | 口径不变；配套追加：三路融合检索 P95 <600ms（含 Graph 路）、实体抽取抽检可用率 >85%、`rag.graph.enabled=false` 零功能回归 |
| Multi-Agent 任务完成率 > 85% | **修正** | 收窄版口径：Mock 场景任务分解演示通过率 ≥80% + 真实工具挂接契约文档完整；**触发条件复活**：真实 OA/ERP/DB 工具立项后升级回 ≥85%（基于真实工具基准集度量） |
| Semantic Cache 命中率 > 30% | 保留 + 扩充 | 追加：命中延迟 P95 降 >40%（对照簇① LT1 基线）、知识库变更后缓存 5min 内失效、7 天真实流量窗口读数 |
| 自动评估 CI 集成（每 PR 自动运行） | 保留（形态已达成） | kb-eval CI 门禁已运行（三分区契约）；本期实质 = 4 指标扩管道 + 人类校准 κ ≥ 0.80 + A/B 双跑报表 |
| MCP Server 兼容性 | **销项 ✅** | Phase 4 4.10 已交付并验证（标准 Client 可调用 + 跨租户 0 泄露） |
| （新增）全项目收口判据 | 新增 | 簇⑥交付：文档三件套（`docs/delivery/`）增量更新覆盖全部新端点/新基建 + 全阶段验收标准复盘归档（18 章联动）——作为项目最终阶段的收尾闭环 |

---

## 四、新增项提案（调研实证驱动）

### N1. 供应商侧 Context Cache 接入（用户定案纳入）

与 5.6 语义缓存**正交**（语义缓存省延迟，前缀缓存省 token）：system prompt + grounding 模板等固定前缀在供应商侧缓存，业界读数省 token 30-50%。

- **主链**：DeepSeek 上下文缓存（主模型 deepseek-v4-flash 承载主流量）；
- **备用/评估链**：百炼显式缓存（qwen3.8-flash 备用与 Judge/意图分类链路），百炼另有隐式缓存自动生效；
- **落码纪律**：两家缓存 API 形态、最小前缀门槛、计费口径落码前源码级核验（DeepSeek 与百炼契约不同，不可互推）；
- **成本**：~1d（装配层改造 + 命中计数指标 + 面板读数）。

### N2. 前六阶段收尾清零簇（用户定案纳入，簇①）

六阶段收官遗留若不带入，Phase 5 将永远背着前置欠账。构成为两部分：

- **用户侧 7 项回传验收登记**（唯一源 = 用户侧待执行项清单，不另立平行清单）：F1 Flyway 现网 baseline / F2 kb-eval IT 复跑 / F3 容器化部署 / M1 监控栈生产部署 / DR1 灾备演练 / SG1 安全组收敛 / **LT1 Gatling 压测四场景（簇③语义缓存与全阶段性能读数的硬前置）**；
- **机器侧伴生件**（≈2d）：rerank 裸 RestClient 补 observation（0.5d，观测链无盲点）、告警 14 条真实流量校准（伴生 M1，7 天窗口误报率 <5%）、TABLE 分类 Faithfulness 观察项读数（0.5d，并入 5.8）、**B5 漏洞 CRITICAL 11 治理**（1d，依赖升级 + 全模块回归；另立跟踪项并入本期消化）。

### N3. A2A 协议支持（前瞻，用户定案纳入）

调研实证：A2A（Agent2Agent）经 Linux Foundation 托管一年已达 150+ 组织、三大云平台集成、企业生产使用；定位与 MCP 互补（MCP = agent-to-tool，A2A = agent-to-agent）；2026 最佳实践 = 先 MCP 后 A2A——项目 MCP 已就绪，正是补位时机。

- **最小形态**：KB Agent Card + 任务端点包装现有 `ragAgentChatClient`（对话能力即任务能力）；身份物化与审计复用 `McpIdentityGuard` / `McpAuditRecorder` 模式（JWT → tenantId → RetrievalContext 参数链）；
- **落码纪律**：a2a-java SDK 与 Spring Boot 4.1 / Spring AI 2.0 GA 兼容性落码前源码级核验，不兼容则降级为协议适配层自研最小集（Agent Card + tasks/send 同步端点）；
- **成本**：~2d；定位为前瞻布局（对外暴露形态从「被当作工具调」补齐「被当作协作者调」）。

### 明确不新增（触发条件驱动，登记在案）

| 项 | 不排期理由 | 重估触发 |
|---|---|---|
| 多模态知识库（音视频） | 百炼虽已支持音视频解析，但 ASR + 分镜抽帧成本高，与当前企业文档语料形态（PDF/DOCX/PPTX/XLSX/MD/TXT/HTML）匹配度低，ROI 不成立；用户定案未纳入 | 真实音视频语料需求提出 |
| Spring AI Alibaba graph 编排 | 另一套编排框架栈，与现有双链路 + Advisor order 基线不兼容，学习成本与收益不成比例 | 现有组合式基座被证明不足（簇⑤实证后） |
| spring-ai-agent-utils 社区库 | 成熟度一般，与自建 Advisor 体系能力重叠 | 同上 |
| 5.4-A 跨链 mode=auto | 真实工具仍 Mock，无跨链混合流量，路由无价值 | 真实工具立项（与 5.3 升级同步） |
| 5.4-B 复杂度三级路由 | 缺轻量模型档位前提（3.2 定案） | 引入第三档轻量模型 |
| 5.5 多知识库路由 | 单租户单库场景无需求 | 第二个真实知识库接入 |
| 3.11 RBAC | 维持缓做定案，无真实组织结构 | 真实部门/角色权限需求 |
| 百炼托管知识库（商业化服务） | 项目自建 RAG 链路全部就位且深度定制（护栏/审计/溯源），托管服务（2026-01 起商业化计费）替代价值为负 | 自建链路维护成本失控 |
| 敏感词面/攻击语料字面扩充 | 红线纪律：产出物不新增字面载荷；G3 长期开放通道（带外导入）已留 | 按运营节奏走带外通道 |

---

## 五、重组后的 Phase 5 任务清单与成簇推进计划

> 沿用优化冲刺与 Phase 4「2-3 关联点成簇」纪律：每簇 = 实现 → 验证 → 文档回写 → 提交 闭环；验证不过不进下一簇。

### 5.1 簇构成

| 顺序 | 簇 | 构成 | 关联逻辑 | 估算 | 验证通道 |
|---|---|---|---|---|---|
| ① | 收尾清零 | N2 全量：用户侧 7 项回传验收登记 + rerank observation 补齐 + 告警 14 条校准（伴生 M1）+ B5 CRITICAL 11 治理 + 5.7/5.11 销项登记 | 前置欠账一次清零；**LT1 压测基线是簇③硬前置** | 机器侧 ~2d + 用户侧执行（窗口 1-2w） | LT1 四场景断言全绿 + P95 基线回填 18 §18.4；告警 7 天误报率 <5%；B5 CRITICAL 清零 |
| ② | 评估进化 | 5.8 Judge 4 指标扩管道 + 人类校准集 50 例（κ ≥ 0.80）+ 5.9 Prompt Git Ops 双跑差异报表 + 5.10 反馈 JSONL 双格式导出 | 评估尺子先升级，为簇③④⑤验收提供度量；共享 Judge 基座 | 8d | 4 指标上线 + 校准报告 + A/B 报表自动产出 + 导出 E2E ≥200 例 |
| ③ | 语义缓存 | 5.6 RediSearch 自建（CacheCheckAdvisor + TTL/document_id 失效）+ N1 Context Cache（DeepSeek 主链 + 百炼备用/评估链） | 两种缓存正交（省延迟 + 省 token），同簇推进 | 4d | 命中率 >30% + 命中延迟 P95 降 >40%（对照 LT1）+ 失效 5min E2E + token 节省面板可读 |
| ④ | GraphRAG | 5.1 新 ECS Neo4j Community 部署 + 实体关系抽取管道（qwen3.8-flash 结构化输出、限流异步）+ 5.2 三路 RRF 融合（复用 `RrfFusion`）+ `rag.graph.enabled` 降级 + 多跳专项测试集 ≥30 例 | 图数据库 → 抽取管道 → 融合检索同域串行 | 10d | 多跳准确率 >80% + 抽取抽检 >85% + 三路融合 P95 <600ms + 开关关闭零回归 |
| ⑤ | Agent 编排 | 5.3 收窄版：Orchestrator-Workers 骨架 + TaskTool 子代理委派 + 3 Mock 子代理演示 + 真实工具挂接契约文档 | 复用双链/Advisor/toolContext 基座；簇② Judge 扩管道做质量验收 | 3d | Mock 委派演示 E2E 通过率 ≥80% + 契约文档评审 |
| ⑥ | 产品化收尾 | 5.12 钉钉群机器人（Stream 模式优先）+ N3 A2A 最小形态 + 5.4/5.5 设计稿归档登记 + 文档三件套增量更新 + 全阶段验收复盘 | 对外触达 + 前瞻协议 + **全项目收口判据**统一收口 | ~4.5d | 机器人 @触发问答 E2E + 标准 A2A Client 调用通过（或降级论证入档）+ 文档评审通过（全项目收官） |

**合计机器侧 ≈31 人日，紧凑窗口 6-7 周**（原规划 8 周/41 人日：销项 2 + 瘦身 4 项省 ~14d，新增项对冲 ~7d）。若需压缩：簇⑥ A2A 可移出保留挂起（-2d）、簇②人类校准集可后置（-1.5d）。

> **簇③ 实现提示（勘察登记）**：① 插入点候选位序在护栏簇（320）之后、记忆/路由（400/440）之前，记忆一致性与审计覆盖是设计关键点，落码前源码级核验并回写 11.2 链序表；② 缓存键须含租户域（跨租户命中是数据泄露，守卫对齐检索侧 fail-closed）；③ 命中路径的输出仍须走输出护栏语义（缓存写入时已消毒，但黑词表面板计数口径需明确）。
> **簇④ 实现提示（调研登记）**：① Neo4j Community 为 GPLv3，内部部署使用合规，分发场景另议；② 2C8G 新机堆参数显式钉死（heap ≈4G + page cache ≈2G），容器化同 `infra/` 纪律（禁 latest / healthcheck / restart）；③ 抽取批处理走低价档模型 + 限流避业务高峰，失败重试幂等；④ 图数据纳入灾备面（备份脚本扩一台新机，随簇交付）。

### 5.2 顺序理由

- **① 先行**：前置欠账清零 + LT1 性能基线确立，后续簇的命中率/延迟降/阈值校准才有对照；机器侧零阻塞，用户侧按既有清单节奏执行；
- **② 次之**：评估尺子先升级——簇③④⑤的验收全部依赖 Judge 扩管道与校准基线；可与簇①后半段并行开工（不依赖 LT1 的子项）；
- **③ 在基线后**：语义缓存的价值度量（命中率/延迟降/省 token）必须对照真实基线，先于 GraphRAG 落地也使簇④延迟归因干净；
- **④ 居中偏后**：唯一新增基建（新 ECS + Neo4j），开工前决策点 = 新 ECS 加购到位；10d 为最大簇，独占一段；
- **⑤ 紧随**：Agent 骨架验收复用簇②评估能力；
- **⑥ 压轴**：对外触达与前瞻协议收口 + 文档三件套增量 + 全项目验收复盘，作为最终阶段的收尾闭环。

### 5.3 每簇 DoD（沿用优化冲刺口径）

1. 代码 + 单测绿（涉及模块 `mvn -q --no-transfer-progress test -am`，新路径并入 3.18 留档的集成测试方案评估范围）
2. 验证通道通过，证据落进度文档任务行
3. 文档回写：进度文档 + 设计回写（见第六节清单）+ CLAUDE.md 同步
4. git 提交（一功能一提交，代码+文档同批）

---

## 六、设计回写候选清单

| 章 | 回写内容 | 时机 |
|---|---|---|
| 第八章 | Phase 5 任务清单与验收标准按本提案修订（销项注记 + 六簇指引），v2.63 修订注记 | **定案后本批执行** |
| 第十章 | 三路融合（Vector + BM25 + Graph，RRF 同口径）设计与降级开关 | 簇④ 落码时 |
| 第十一章 | 11.2 链序表更新（CacheCheckAdvisor 位序 + 短路语义）；§11.5.5 Multi-Agent 收窄骨架设计；5.4-A/B 设计稿归档锚点确认 | 簇③/⑤ 落码时 |
| 第十三章 | 缓存命中/未命中指标族 + Graph 路观测埋点 + Context Cache 命中计数面板位 | 簇③/④ 落码时 |
| 第十六章 | 4 指标定义与阈值 + 人类校准方法论（κ 口径）+ A/B 双跑形态 | 簇② 落码时 |
| 第十七章 | 新 ECS + Neo4j 部署形态（compose 纪律 / 灾备扩展 / 安全组） | 簇④ 落码时 |
| 第十八章 | 全项目收口判据（文档三件套增量 + 全阶段验收复盘） | 簇⑥ 落码时 |

---

## 七、定案记录（2026-08-22 用户拍板）

1. **GraphRAG 基建**：✅ 加购第二台 ECS + 自托管 Neo4j Community（生态最成熟、免费、独占新机；FalkorDB / AuraDB Pro / Apache AGE 调研登记为备选不选）
2. **Multi-Agent（5.3）**：✅ 收窄为编排骨架 + 真实工具挂接契约留口（Spring AI 2.0 原生组合式，不引第三方编排框架），真实工具立项后自动升级回原验收
3. **语义缓存（5.6）**：✅ 现有 Redis 8 自建（RediSearch + 余弦，零新增基建），阿里云 Tair 语义缓存网关登记为商业备选
4. **新增项**：✅ 纳入三项——N1 供应商侧 Context Cache（DeepSeek + 百炼）、N2 前六阶段收尾清零簇、N3 A2A 协议前瞻；多模态（音视频）不纳入登记挂起
5. **周期口径**：✅ 机器侧 ≈31 人日，六簇推进，紧凑窗口 6-7 周

**定案后动作**：第八章 Phase 5 小节按本提案修订（v2.63）；07 卷头部追加复审导航注记、索引状态行更新；簇①（收尾清零）为开工首项。

---

## 附录 A：调研来源清单（2026-08-22，多路并行调研）

**2026 企业级 RAG 格局**：Enterprise RAG Guide（bloss0m.com，2026-07，hybrid-first 基线 + 按需叠加 Graph/Agentic）· Build RAG Systems in 2026: 8 Architecture Patterns（aithinkerlab.com，2026-06）· Agentic RAG: Surmounting the Limits of Traditional RAG（redis.io，2025-12，延迟/成本/可靠性五挑战）· Pipeline vs Agentic vs KG RAG（Medium，2026-02）· Best Enterprise RAG Platforms 2026 Buyer's Guide（onyx.app，2026-05）· Multi-agent RAG 架构与信任框架综述（arXiv 2601.05264）
**Spring AI Agent 能力**：Spring AI 2.0.0 GA 发布公告（spring.io，2026-06-12，工具循环升入 advisor 链/统一工具调用/渐进发现）· Building Effective Agents 参考文档（docs.spring.io，Chain/Parallelization/Routing/Orchestrator-Workers/Evaluator-Optimizer 五模式）· Spring AI Agentic Patterns 系列 Part 1 Agent Skills（2026-01-13）/ Part 4 Subagent Orchestration（2026-01-27，Task tool 子代理/上下文隔离/并发子代理）· Top GenAI Frameworks for Java 2026（xavidop.me，2026-04）
**语义缓存**：Redis Semantic Cache 用例文档与系列博客（redis.io，向量 + 余弦、命中率与延迟读数）· Top Semantic Caching Solutions 2026（getmaxim.ai）· Semantic Cache for LLMs: When to Ship, When to Skip（respan.ai，2026-05）· Semantic Caching with SpringBoot & Redis（foojay.io）· Semantic Caching Saved Us $14K/Month（birjob.com，成本案例）· 阿里云 Tair 语义缓存网关文档（help.aliyun.com，精确 + 语义双策略 / AI Cache 全托管）· 阿里云 API 网关 AI 缓存文档
**百炼商业生态**：知识库（RAG）商业化公告（2026-01-04 起计费，规格费 + Token 双轨）· 知识库计费说明 · 上下文缓存 Context Cache 文档（隐式/显式）· 显式缓存最佳实践 · qwen3-rerank 模型文档 · 百炼产品月报 2026-01（知识库全面商业化 / 音视频多模态 / 检索 QPS 弹性扩容）
**GraphRAG 基建**：What is GraphRAG（neo4j.com）· NODES AI 2026 Agentic GraphRAG（自主知识图谱构建 + 自适应检索）· Neo4j 定价（AuraDB Professional ~$65/GB/月起）· FalkorDB（Redis 基座 GraphRAG SDK）· Open Source KG & GraphRAG Databases Compared（arcadedb.com，2026-07，Kùzu/ArcadeDB/Neo4j Community）· Efficient KG Construction and Hybrid Retrieval at Scale（arXiv 2507.03226）
**A2A 协议**：Agent2Agent Protocol 发布公告（developers.googleblog.com，2025-04，与 MCP 互补定位）· Linux Foundation 周年里程碑新闻稿（2026-04，150+ 组织 / 三大云平台 / 企业生产使用）· A2A 协议官方文档（a2a-protocol.org）· Agent 通信协议对比：A2A / MCP / ACP / ANP（zylos.ai，2026-02）

## 附录 B：代码侧复用落点勘察清单（2026-08-22 三路勘察结论）

| 特性域 | 复用落点 | 出处 |
|---|---|---|
| 语义缓存 | `EmbeddingModel` Bean（qwen3.7-text-embedding，检索同源）；Redis 8 内置 Query Engine；Redisson starter 已装配；`AiBusinessMetrics` 已预留 `rag.retrieval.cache.hit` 注释登记位 | kb-ai-core / kb-infrastructure |
| 语义缓存插入点 | Advisor 链序 10→1000 标准化（护栏簇 → 记忆 400 → 路由 440 → 门控 500）；`RetrievalGateAdvisor` 组合式包裹先例（旁路整段管线的成熟模式） | 11.2 链序表 |
| GraphRAG 融合 | `RrfFusion`（K=60）；`HybridDocumentRetriever` 双路并行 + `Future.get(timeout)` 降级模板；`hybridRetrievalExecutor` 虚拟线程执行器 Bean | kb-ai-core/retriever/ |
| Multi-Agent | 双 ChatClient（rag/tool）+ @Qualifier 纪律；`toolContext` 通道（`ToolContextKeys`）；`SmartRoutingChatModel` ChatModel 装饰器先例；`kb-ai-agent` 模块定位已登记 | kb-ai-core / kb-ai-agent |
| A2A / 钉钉触达 | `McpIdentityGuard` 身份物化 + `McpAuditRecorder` 轻量审计 + `McpRateLimiter` 独立配额桶模式；`/chat` 对话端点含全护栏链 | kb-ai-agent/mcp/ |
| 评估进化 | `RetrievalProbe` 接口（Ordered 自动选择）；Judge 跨厂商基座（qwen3.8-flash）；三分区门禁 + `eval.ci.enabled`；`eval.retrieval-only` 秒级通道 | kb-eval |
| 性能度量 | `rag.retrieval.latency` / `rag.ttft` Timer（p50/p95/p99）；OTLP + Langfuse 生产化；kb-loadtest Gatling 四场景（LT1 基线产出方） | kb-ai-core/metrics/ / kb-loadtest |
