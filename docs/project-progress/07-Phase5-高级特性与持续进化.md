## Phase 5：高级特性与持续进化（第 17-24 周）

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。
>
> **复审导航（2026-08-22 定案）**：下表 12 项已经立项复审逐条裁决（销项 5.7/5.11、收窄 5.3/5.12、调整 5.1/5.9、归档 5.4、降级登记 5.5、保留 5.2/5.6/5.8/5.10），并新增三项（供应商侧 Context Cache / 前六阶段收尾清零簇 / A2A 前瞻）、重组为六簇推进（收尾清零 → 评估进化 → 语义缓存 → GraphRAG → Agent 编排 → 产品化收尾）。**推进以复审方案为基线**：[Phase 5 复审与规划方案（调研实证版）](../project-optimization/Phase%205%20复审与规划方案（调研实证版）.md)；路线图修订见 08 章 v2.63。本卷下文任务表作为原始登记保留，各任务行进展回填仍落本卷。

**目标**：GraphRAG、Multi-Agent、智能路由、反馈闭环

### 任务清单（12 项）

| # | 任务 | 负责模块 | 工时估算 | 验收标准 | 完成情况 |
|---|------|---------|---------|---------|---------|
| 5.1 | 部署 Neo4j + 知识图谱构建管道 | kb-infrastructure/kb-etl | 5d | 实体关系自动抽取入库 | |
| 5.2 | 实现 GraphRAG 混合检索（Vector + Graph 双引擎） | kb-ai-core | 5d | 多跳推理问题可正确回答 | |
| 5.3 | 实现 Multi-Agent Orchestrator（主Agent+子Agent 模式） | kb-ai-core | 5d | 复杂任务自动分解执行 | |
| 5.4 | 实现查询意图识别 + 自适应路由（分类器模型） | kb-ai-core | 3d | 不同意图分流到不同策略 | ✅ 收窄版完成（2026-08-08，用户拍板，当日 E2E 通过；**剩余两项 2026-08-09 缓做定案**，与 3.18 同步前移至 Phase 4 立项前：A 跨链 mode=auto 价值拐点在真实工具落地，作 Phase 4 伴生项；B 复杂度三级路由缺轻量档模型前提且无成本痛点，触发条件=引入轻量档位）。**动因**：3.15 E2E 实证 ① grounding 强约束（「必须且只能基于【参考资料】」）语义层压过 Memory(400) 注入历史——「我刚才问了什么」被拒答（bm25 召回 10 条噪声未触发空证据模板，是强约束拒答非记忆故障）；② 闲聊/元问题白付改写+检索+重排 ~5s 前置开销。**落地形态**（设计锚点 §11.4，四处修正/强化见 11 章 v2.13）：QueryRoutingAdvisor(order 440) 双层分类——L1 正则快路（整句全匹配纯寒暄/致谢/道别/助手元问题，≤15 字符）零 LLM；L2 结构化分类 `entity(IntentResult)`（直注 agentChatMemory 经 CONVERSATION_ID 读最近 6 条历史，不依赖 MessageChatMemoryAdvisor 内部排序）；**分类与改写合并单次 LLM 调用**——KNOWLEDGE 路径预写 `RetrievalContext.rewrittenQuery`，RewriteCapturingQueryTransformer 识别后跳过自身调用，知识问零新增延迟；RetrievalGateAdvisor(order 500) 实现 CallAdvisor+StreamAdvisor 组合式包裹 RetrievalAugmentationAdvisor（源码核验框架类 final 不可 extends，草图「自身读标记短路」不可行）——skipRetrieval 时 chain.nextCall/nextStream 直接放行旁路整套管线；**fail-open 纪律**（分类异常/解析失败/未知 intent 回落完整检索，最坏=现状）；AiBusinessMetrics + rag.routing.chitchat/knowledge 分流计数；闲聊路径免 TRACE 帧（对齐「不推空帧」纪律）；`rag.routing.intent.enabled`/`history-size` 配置（总开关 false 回退现状）；defaultSystem 双形态措辞（知识问证据约束仍由 GROUNDING_PROMPT 每请求注入保证）。mode 契约/前端零改动，kb-eval 独立 chatClient 零影响。设计回写 11 章 v2.13；新增 21 单测（分类 13/门控 5/装饰器 2/指标 1），全模块 211 绿。**E2E 通过（2026-08-08，用户验证）**：寒暄/元问题 CHITCHAT 免检索直答（元问题正确复述上一问）、知识问回归完整溯源、空证据拒答负向通过、rag_routing_* 计数匹配（见《00-每日进度状态记录》2026-08-08 状态行）。**剩余形态设计锚点**：A 跨链 mode=auto 路由器位于两链之上（Controller/Service 层选链，异于链内 440）+ approvedToolCallId 非空硬路由 tool 链 + fail-open 回落 rag；B 需先引入轻量模型档位（3.2 定案「双模型下形同虚设」）+ Prompt options 重建（坑位⑭）+ kb-eval 防误路由护栏 |
| 5.5 | 实现多知识库动态路由（不同领域独立 VectorStore） | kb-ai-core | 3d | 按领域自动选择知识库 | |
| 5.6 | 实现 Semantic Cache（Redis RediSearch + 语义相似度） | kb-ai-core | 3d | 相似问题缓存命中率 > 30% | |
| 5.7 | 扩充 Golden Dataset 至 200+ 问答对（基线已在 Phase 2.16 建立） | kb-eval | 3d | 测试集覆盖主要场景 | ✅ **销项（2026-08-23 登记，Phase 5 复审裁决）**：提前达成——现 237 条（干净 110 + 注入 127，三分区门禁全绿），超额完成（200+ 目标），复审方案 §二 裁决留痕 |
| 5.8 | 扩充 LLM-as-Judge 评估管道（指标全集见第十六章） | kb-eval | 3d | 全指标自动评分 + 人类校准 85-90% 一致率 | |
| 5.9 | 实现 A/B 测试框架（Prompt 效果对比） | kb-eval/kb-ai-core | 3d | 双版本效果量化对比 | |
| 5.10 | 实现反馈闭环导出管道（JSONL SFT 格式） | kb-admin | 2d | 可用于模型微调的数据导出 | |
| 5.11 | 实现 MCP Server 宿主（知识库对外暴露为 MCP 服务） | kb-api | 3d | 第三方 Agent 可调用知识库 | ✅ **销项（2026-08-23 登记，Phase 5 复审裁决）**：已在 Phase 4 4.10 提前交付（v2.60，§11.8 三件套 + 身份守卫 + 独立限流审计，标准 Client 兼容与跨租户隔离验证通过），复审方案 §二 裁决留痕 |
| 5.12 | 企业微信/钉钉集成适配 | kb-api | 3d | 工作 IM 内直接问答 | |

### Phase 5 验收标准

| 指标 | 目标值 |
|------|--------|
| GraphRAG 多跳推理准确率 | > 80% |
| Multi-Agent 任务完成率 | > 85% |
| Semantic Cache 命中率 | > 30% |
| 自动评估 CI 集成 | 每 PR 自动运行 |
| MCP Server 兼容性 | 标准 MCP Client 可正常调用 |

---

### 簇① 收尾清零（推进记录）

> 构成与 DoD 见复审方案 §五；机器侧小件先行，用户侧 7 项回传（清单唯一源）并行。

| 项 | 内容 | 状态 |
|---|------|------|
| 5.7/5.11 销项登记 | 上表任务行已标（提前收官留痕） | ✅ 2026-08-23 |
| rerank observation 补齐 | `kb.rerank` 观测包裹（v2.32 ④ 登记项清偿，13 章 v2.64）；单测 +2，kb-ai-core 242 绿 | ✅ 2026-08-23 机器侧（E2E 观察点 = Langfuse 检索链树下 `rerank qwen3-rerank` span，随下轮运行态核验） |
| B5 漏洞治理 | 11 项 CRITICAL 清零形态：五构件族版本覆盖 + kotlin-stdlib-common 声明点排除（12 章 v2.64）；全模块 699 单测绿 | ✅ 2026-08-23 机器侧；**复扫回传同日闭环**：8 CVE 摘 5（CRITICAL 11→3），残留三枚定性 = kotlin 修复线实为 2.4.20（未 GA 不升 RC，待上游，清单 B5-4）+ milvus 双 CVE 服务端 CPE 误判（`dependency-check-suppressions.xml` 族级限定抑制，本地验证复扫 CRITICAL→1；服务端真实治理 = 清单 B5-3 ECS Milvus ≥2.6.10）；日志三项报错（RetireJS/.NET/OSS Index）定性数据源噪声无大碍；12 章 v2.65 |
| TABLE 分类 Faithfulness 观察项 | 读数通道核验已备（簇④ E1：报告「生成侧分类分解」+ 单维不崩地板 3.5）；**销账判据定案**：并入簇② 5.8 首轮全量复跑读数——TABLE 分类 F ≥3.5 且门禁绿 → 观察项销账（3.800 读数归 Judge 噪声带）；<3.5 → 单维崩盘治理立项。**不在本簇跑数理由**：计费伴随运行态，且与用户侧 LT1 压测同期争 ECS/LLM 资源 | 📋 判据定案，销账并簇② |
| 告警 14 条校准 | 伴生 M1（监控栈生产部署 + 7 天真实流量窗，误报率 <5%） | ⏳ 依赖用户侧 M1 |
| 用户侧 7 项回传登记 | F1-F3/M1/DR1/SG1/LT1（LT1 为簇③硬前置） | 🟡 2/7：**LT1 ✅ 2026-08-23 通过**（四场景缺省并发全达验收线、零失败全 DONE，18 §18.4 实测列回填 v2.65，**簇③硬前置解除**；数值明细待报告补录）+ **B5-2 ✅ 复扫入档**（见 B5 行）；余 6 项待回传 |
| B5 残留跟踪 | B5-3 ECS Milvus 服务端 ≥2.6.10（用户侧待执行）+ B5-4 kotlin 2.4.20 GA 后版本抬升复扫（待上游触发，机器侧）——两项不阻塞任何簇 | ⏳ 清单跟踪 |

