## Phase 5：高级特性与持续进化（第 17-24 周）

> 本文档为《项目阶段推进任务清单完成记录》2026-08-21 拆分子卷（仅结构调整，内容为原始记录逐字保留）；索引导航见[主文档](./项目阶段推进任务清单完成记录.md)。

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
| 5.7 | 扩充 Golden Dataset 至 200+ 问答对（基线已在 Phase 2.16 建立） | kb-eval | 3d | 测试集覆盖主要场景 | |
| 5.8 | 扩充 LLM-as-Judge 评估管道（指标全集见第十六章） | kb-eval | 3d | 全指标自动评分 + 人类校准 85-90% 一致率 | |
| 5.9 | 实现 A/B 测试框架（Prompt 效果对比） | kb-eval/kb-ai-core | 3d | 双版本效果量化对比 | |
| 5.10 | 实现反馈闭环导出管道（JSONL SFT 格式） | kb-admin | 2d | 可用于模型微调的数据导出 | |
| 5.11 | 实现 MCP Server 宿主（知识库对外暴露为 MCP 服务） | kb-api | 3d | 第三方 Agent 可调用知识库 | |
| 5.12 | 企业微信/钉钉集成适配 | kb-api | 3d | 工作 IM 内直接问答 | |

### Phase 5 验收标准

| 指标 | 目标值 |
|------|--------|
| GraphRAG 多跳推理准确率 | > 80% |
| Multi-Agent 任务完成率 | > 85% |
| Semantic Cache 命中率 | > 30% |
| 自动评估 CI 集成 | 每 PR 自动运行 |
| MCP Server 兼容性 | 标准 MCP Client 可正常调用 |

