# Phase 5 簇⑤ Agent 编排（Multi-Agent Orchestrator 收窄版）实施方案（批次推进版）

> **版本**：v1.1（定案稿）· **日期**：2026-09-05 · **工时**：3d · **模块跨度**：kb-ai-agent / kb-api / kb-ai-core（指标）/ frontend / docs
> **性质**：簇⑤落码执行基线（现状勘察 + 架构设计 + 待定案决策点）。复审定案出处：`docs/project-optimization/Phase 5 复审与规划方案（调研实证版）.md` §二 5.3 / §五簇表；批次进展回填 07 卷簇⑤段。
> **官方路径核验（2026-09-05）**：Spring AI 2.0.1 GA 无框架级 Agent 抽象，[Building Effective Agents 五模式](https://docs.spring.io/spring-ai/reference/api/effective-agents.html) 全为纯 Java 组合；[Agentic Patterns Part 4 Subagent Orchestration](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents) 的 Task tool 模式 = 主 Agent 仅持 task 工具、子代理各持隔离上下文/独立 system prompt/可差异化模型。与定案原文「主 Agent + TaskTool 子代理委派」完全对齐，自建成本即 3d 底气。
> **分支纪律**：沿簇④先例拟分支 `phase5-cluster5-orchestrator`（开工时按 main 状态定，3d 小簇亦可直推 main——开工时定）。

---

## 一、定案记录（2026-09-05，用户拍板）+ 决策点选项集（留档）

1. **D1 入口形态** = A：`mode: agent` 第三链——orchestratorChatClient 独立装配，主 Agent 仅持 task 工具，既有 rag/tool 两链逐字节不变；
2. **D2 子代理模型分工** = A：差异化挂载——知识检索/数据查询挂 `fallbackChatModel`（qwen3.8-flash），报告生成挂 `smartRoutingChatModel`；
3. **D3 编排开关缺省值** = A：`rag.orchestrator.enabled` 缺省 false，关闭态 `mode: agent` → 400 `ORCHESTRATOR_DISABLED`，验收后用户侧自行开；
4. **D4 Mock 工具留存** = A：保留至真实 OA/ERP 工具立项时替换（L1 换实现，HITL 机制层/链路层不动）；簇⑤ 数据查询子代理复用读工具；
5. **D5 并发子代理 / D6 验收形态**：按推荐默认执行（无异议）——D5 落码核验 `DefaultToolCallingManager` 后回填结论；D6 演示任务集 10 例 E2E 用户自测回传。

### 决策点选项集（裁决留档，推荐项置首位）

### D1 入口形态

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **新增第三链 `mode: agent`**——orchestratorChatClient 独立装配，主 Agent 仅持 task 工具 | 零协议破坏（TOOL_CALL 事件/审计 mode 列/前端卡片全复用）；既有 tool 链行为逐字节不变；与 rag/tool 双链显式分流架构同构（v2.9 先例）；Part 4 模式主张主 Agent 上下文不被叶子工具 schema 污染 |
| B | tool 链内改造（defaultTools 换 TaskTool，Mock 工具下沉淀到子代理） | 省一个 ChatClient Bean；但既有 tool 链行为变更（回归面扩大）+ 混挂污染主 Agent 决策面 + 审计 mode 语义漂移 |
| C | 不进对话链，仅内部编程式 Orchestrator-Workers API（官方三段式：分解→并行→合成） | 无前端改动；但「Agent 式自主委派」退化成「Workflow 式预分解」，与定案「主 Agent + TaskTool 委派」不符，演示价值低 |

### D2 子代理模型分工

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **差异化**：知识检索/数据查询子代理挂 `fallbackChatModel`（qwen3.8-flash），报告生成挂 `smartRoutingChatModel` | 演示「子代理差异化模型」特性（Part 4 卖点）+ 轻任务挂备有先例（路由/改写 v2.83）+ 检索/查询属轻任务省成本，报告生成要主答质量 |
| B | 全挂 smartRoutingChatModel | 形态最简；放弃差异化演示与成本分层 |
| C | 全挂 fallbackChatModel | 最省；报告质量受损（演示观感差） |

### D3 编排开关缺省值

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | `rag.orchestrator.enabled` **缺省 false**；关闭态 `mode: agent` → 400 `ORCHESTRATOR_DISABLED`（显式拒绝不静默回落） | 对齐簇③④ 纪律：演示骨架未验收 + 真实工具未挂接前不默认暴露；rag/tool 两链逐字节零变化；验收后用户侧自行开 |
| B | 缺省 true | 演示即生产形态；但未经 E2E 验收的功能默认暴露不符合项目灰度惯例 |

### D4 Mock 工具留存（本轮专议，详析见 §五）

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **保留至真实工具立项时替换**（L1 换实现，HITL 机制层/链路层不动）；簇⑤ 数据查询子代理复用 Mock 读工具 | Mock 是工具调用/HITL/双链/编排四项能力的唯一演示载体；维护成本近零；契约参照价值（11.2.1 定稿「契约按真实系统设计，逐个替换」）；与 5.3/5.4-A「真实工具立项」复活触发器同款 |
| B | 簇⑤ 落地后移除写工具（submitLeaveRequest）仅留读工具 ×2 | 减一个 HITL 触发面；但 HITL 能力失去端到端演示载体（前端审批卡片死 UI） |
| C | 簇⑤ 后 Mock 与 tool 链整体退役 | 双链架构（v2.9 用户拍板）回退 + 编排链失去落点与工具基座，不可行 |

### D5 并发子代理

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | 落码前核验 `DefaultToolCallingManager` 对同消息多 tool call 的执行形态：并行则自然获得；串行则收窄版接受（Mock 子代理秒级）+ 契约文档登记并发升级路径 | 零设计赌注；核验结论回写本文档 |
| B | TaskTool 入参支持子任务批列表，工具内部 `hybridRetrievalExecutor` 式并行 | 主 Agent 单次委派一批；但把「分解决策」部分硬编码进工具，形态混杂 |
| C | 钉死串行不核验 | 放弃免费的可能并行 |

### D6 验收形态

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | 演示任务集 10 例 + E2E 步骤交付用户自测回传（项目 E2E 纪律：不启动服务，功能批完成即交付） | kb-eval 只依赖 kb-ai-core 不依赖 kb-ai-agent，编排链评估**不可达**（模块边界即隔离）；判定标准文档化（委派对象正确 + 参数合理 + 结果综合） |
| B | 进 kb-eval 评估门禁 | 需破模块依赖边界（kb-eval 加依赖或 HTTP 探针形态）——为演示骨架破评估容器独立性，不成比例；登记为真实工具升级项 |
| C | 机器侧自测脚本（同步端点 + 断言） | 需起服务，违背 E2E 自测形态定案 |

---

## 二、簇⑤边界与不可破纪律

### 2.1 边界（Phase 5 方案定案）

| 子项 | 交付物 | 落模块 |
|---|---|---|
| Orchestrator-Workers 骨架 | 主 Agent + TaskTool 子代理委派 + SubAgentRegistry | kb-ai-agent |
| 3 个 Mock 子代理演示 | 知识检索 / 数据查询 / 报告生成 | kb-ai-agent |
| 真实工具挂接契约文档 | §11.5.5 扩写（含契约大纲，见 §七） | docs |
| 前端入口 | Chat 页 mode 第三选项 + 委派过程可视化（TOOL_CALL 卡片复用） | frontend |

**明确不做**（登记）：跨层 HITL（子代理挂起审批→汇聚→续跑）、子代理 usage 聚合计账、子代理 trace 强制合树、kb-eval 门禁接入、spring-ai-agent-utils / Spring AI Alibaba graph 引入。均为真实工具升级路径项。

### 2.2 不可破纪律

1. **`rag.orchestrator.enabled` 缺省 false**——关闭态编排族 Bean 缺位，rag/tool 两链逐字节零变化（簇③④ 同款条件装配纪律）
2. **租户身份链不断**：主请求 RetrievalContext（tenantId/userId）→ AgentOrchestratorService 组装 toolContext → TaskTool 下传 → 子代理内检索/数据工具消费同一身份，fail-closed 两层语义不变（有 ctx 无租户 → 空结果）
3. **toolContext 仅编排服务组装**（物理消除凭证泄露进 rag 链——v2.9 纪律延伸）
4. **子代理静态 Spec 不含 task 工具**——递归委派物理不可能，层级恒两层
5. **多 ChatClient Bean 纪律**：新 Bean `orchestratorChatClient`（第五个），所有注入点显式 @Qualifier
6. **指标零租户标签**（子代理名 tag 仅 3 枚举值，无基数风险）
7. **产出物不含攻击字面**（红线，本簇无安全语料面，例行重申）

---

## 三、现状基线与复用落点

| 复用点 | 现状 | 簇⑤ 用法 |
|---|---|---|
| `toolAgentChatClient` 链形态 | Audit(10)→TokenBudget(30)→RateLimit(100)→OutputGuardrail(110)→InputSanitize(300)→SemanticInjection(320)→Memory(400)→ToolCallingAdvisor(1000) | 编排链同构装配（仅 defaultTools 不同 = task 单工具） |
| `agentToolCallingAdvisor`(1000) 自建 Bean | advisor 无状态 | **同一 Bean 实例共享挂载**编排链 |
| `agentChatMemory` + sessionId 协议 | 跨链历史互通 | 编排链挂 Memory(400)，多轮编排会话复用 |
| `smartRoutingChatModel` / `fallbackChatModel` | 主备容灾装饰器 + 轻任务挂备先例 | 主 Agent/报告生成挂前者；检索/数据查询子代理挂后者（D2-A） |
| `ToolContextKeys` 通道 | tenantId/userId/approvedToolCallId/retrievalContext 四键 | TaskTool 下传身份（复用前两键 + retrievalContext） |
| `HybridDocumentRetriever`→`RrfFusion`→rerank | 双路[+Graph]混合检索 | 知识检索子代理的 searchKnowledge 直调（McpKnowledgeTools.search 直调模式同构） |
| `EnterpriseMockTools` 读工具 ×2 | queryEmployee / queryLeaveBalance | 数据查询子代理工具集（**拆类只挂读**，见 §4.4） |
| SSE TOOL_CALL 命名事件 + 前端 ToolCallCard | HITL 审批卡片（PENDING_APPROVAL 态） | 委派过程 = 工具调用记录，卡片零改动自然渲染 |
| `RetrievalContext.ToolCall` 快照 + AuditTraceAdvisor | 流末按 status 分桶计 `rag.tool.call.*` | task 委派同通道计指标 + 审计 mode='agent' 行落库 |

---

## 四、架构设计

### 4.1 总体形态：mode=agent 第三链（D1-A）

```
请求 mode: agent（缺省 rag 不变；resolveMode 扩枚举 rag|tool|agent，非法值仍 400 INVALID_MODE）
  → AgentController 分流 → AgentOrchestratorService（同步/流式两形态对齐 ToolChatService 契约）
  → orchestratorChatClient：
      Audit(10)→TokenBudget(30)→RateLimit(100)→OutputGuardrail(110)
      →InputSanitize(300)→SemanticInjection(320)→Memory(400)→ToolCallingAdvisor(1000 复用 Bean)
      defaultTools = [ task ]        ← 仅一个委派工具，主 Agent 上下文零叶子工具 schema
         │
         ▼ 工具循环内委派
      TaskTool.task(subagent, description, toolContext)
         → SubAgentRegistry 取 Spec → 子 ChatClient（轻链，见 4.2）→ 隔离上下文执行
         → 返回结果文本回主 Agent（失败/超时返回错误描述文本，不抛异常击穿主链）
```

- **主 Agent 挂 Memory(400)**：多轮编排会话（同 sessionId 跨 mode 互通）；**子代理不挂记忆**——每次委派独立上下文，即 Part 4 的 context isolation 语义。
- **SSE 协议零变更**：TOKEN/ERROR/DONE 同形；TOOOL_CALL 事件天然承载委派（toolName=task，args 含 subagent/description，经 RetrievalContext.ToolCall 记录投影，与 EnterpriseMockTools.recordToolCall 同款模式）；TRACE 帧不推（同 tool 链）。
- **审计**：kb_audit_log.mode 落 'agent'（列为字符串预期零 DDL；AuditTraceAdvisor 及 Bad Case 查询端点对 mode 的枚举/白名单假设为落码核验点 N1）。

### 4.2 组件清单（kb-ai-agent 新包 `orchestration/`）

| 组件 | 职责与契约 |
|---|---|
| `SubAgentSpec`（record） | `name` / `description`（供主 Agent 选择）/ `systemPrompt` / `toolCallbacks` / `chatModel` / `timeoutSeconds`——子代理静态描述子；**不含 task 工具**（递归物理防护） |
| `SubAgentRegistry` | Map<name, Spec> + 启动时装配 3 个 Mock 子代理；渲染「子代理清单（name+description）」进主 Agent system prompt（静态拼接）；**真实工具挂接点 = 新增一条 Spec 注册，主 Agent prompt 自动含入，零改动** |
| `TaskTool` | `@Tool task(subagent, description, ToolContext)`：身份经 toolContext 下传构建子调用上下文；按 Spec 取**缓存的**子 ChatClient（工具集静态，实例可复用）；有界超时（缺省 60s，`rag.orchestrator.subagent.timeout-seconds`）；结果/错误均以文本返回主 Agent 决策（重试/换路/如实告知）；委派记录 `recordToolCall`（status EXECUTED/FAILED）入 RetrievalContext 快照 |
| `OrchestratorChatClientConfig` | `@ConditionalOnProperty(rag.orchestrator.enabled=true)` 条件装配：`orchestratorChatClient` + 主 Agent system prompt（编排者角色：分析任务→选择子代理→委派→综合；知识类问题必须委派检索不得凭记忆作答）；子 ChatClient 缓存构建 |
| `AgentOrchestratorService` | 同步 `chatOrchestrator` / 流式 `chatStreamOrchestrator`，对齐 ToolChatService 方法契约；组装 toolContext（tenantId/userId/retrievalContext——不含 approvedToolCallId，编排链无 HITL） |

**子代理 ChatClient 轻链**：`ChatClient.builder(spec.chatModel()).defaultSystem(spec.systemPrompt()).defaultTools(spec.tools())` 直构——不挂 Memory（隔离）/ Audit（主请求审计行已含委派记录）/ TokenBudget·RateLimit（主请求已计账，避免双计）。**身份安全不省**：子代理工具消费的租户过滤经 toolContext 全链下传。

### 4.3 三个 Mock 子代理

| 子代理（name） | 工具 | 模型（D2-A） | 复用/新建 |
|---|---|---|---|
| `knowledge-searcher` 知识检索 | searchKnowledge（直调 HybridDocumentRetriever→RRF→rerank，零额外 LLM 主答）/ getDocument（PG 读 + 租户 fail-closed + 软删过滤） | fallbackChatModel | 薄 @Tool 委派新写（隔离 MCP 域语义；McpKnowledgeTools 直调模式同构，落码核验其身份消费签名后定复用粒度） |
| `data-query` 数据查询 | queryEmployee / queryLeaveBalance | fallbackChatModel | **EnterpriseMockTools 拆类**：`EnterpriseMockReadTools`（读×2）+ `EnterpriseMockWriteTools`（写×1 HITL）——tool 链 defaultTools 两个都挂（行为不变），子代理只挂读类（方法级挑选在 2.0 无现成过滤器，拆类为零核验成本稳妥路径；契约文档说明） |
| `report-writer` 报告生成 | 无（纯 LLM 写作，输入 = 主 Agent 汇聚的检索/查询结果） | smartRoutingChatModel | — |

### 4.4 观测与指标（kb-ai-core AiBusinessMetrics 新族）

- `rag.orchestrator.delegation.total / success / fail`（Counter，tag=subagent，3 枚举值）
- `rag.orchestrator.subagent.duration`（Timer，tag=subagent）
- 审计行 tool_calls 快照天然含每次委派（subagent/description/结果状态）
- trace 合树：子代理调用在工具执行线程内同步发起，observation 传播形态落码核验（坑位㉗ Reactor Context 桥接先例）；不合树则登记为已知取舍（升级路径项）

### 4.5 开关与零回归

- `rag.orchestrator.enabled=false`（缺省）：orchestration 族 Bean 缺位（@ConditionalOnProperty），AgentController 对 mode=agent 返回 400 `ORCHESTRATOR_DISABLED`；rag/tool 两链与关闭前逐字节一致
- 前端 mode 切换在开关关闭时仍显示第三选项（后端 400 有明确 errorCode，前端 toast 提示）——或按启动配置隐藏（落码取简：恒显 + 后端拒绝，避免配置面耦合前端）

---

## 五、Mock 工具逻辑留存分析（2026-09-05 专议，留档）

### 5.1 三层解耦视图

| 层 | 构成 | 性质 |
|---|---|---|
| **L1 Mock 演示层** | `EnterpriseMockTools`（151 行：读×2 + 写×1 + 静态 Map 假数据） | 纯演示数据，契约按真实 OA/ERP 设计（11.2.1 定稿） |
| **L2 HITL 机制层** | `ToolApprovalService`（Redis 账本 TTL 10min/一次性消费/tenant+user 绑定 fail-closed）+ `ToolApprovalController`（POST /api/v1/tools/approvals/{id}/approve）+ `ToolContextKeys.APPROVED_TOOL_CALL_ID` 凭证通道 + SSE TOOL_CALL 协议 + 前端 ToolCallCard 审批卡片（confirmed → 二次对话携凭证） | **能力层，不依赖 Mock 数据**——真实写工具挂接时整套复用（3.4 审批沙箱定稿语义） |
| **L3 链路架构层** | `toolAgentChatClient` + mode:tool 分流 + 审计 mode 列 + `rag.tool.call.*` 指标族 | v2.9 用户拍板的双链架构一翼 |

### 5.2 引用面（grep 实测）

- L1 仅两处生产引用：`ToolAgentChatClientConfig`（defaultTools 装配）+ 自身测试；L2 经 kb-api `ToolApprovalController` 暴露端点；前端 Chat.vue（mode 切换 + 审批确认回传 `ask('确认执行上述操作', { mode:'tool', approvedToolCallId })`）/ ToolCallCard.vue / chat.ts / api/index.ts。
- **移除 L1 的连锁**：tool mode 变零工具空转链（ToolCallingAdvisor 空转、mode 分流失去意义）→ L2 全部退化为死码（审批端点/账本/卡片永无触发，比演示态更糟）→ `rag.tool.call.*` 指标恒零 → 簇⑤ 数据查询子代理失去演示基座 → 契约参照价值丢失。
- **连 L2/L3 一并拆**：双链架构回退 + 簇⑤ 编排链失去落点（编排链 = tool 链工具循环基建的深化，TaskTool 走同一 ToolCallingAdvisor）。
- **维护成本实测**：静态 Map 假数据、零外部依赖、不用即零执行（rag 链零触达）、测试稳定——近零。

### 5.3 结论（对应 D4）

**不可移除**。唯一正确时机 = 真实 OA/ERP/DB 工具立项时**替换**（L1 换实现，L2/L3 不动）——与 5.3 原验收 >85% 复活、5.4-A 跨链 auto 路由复活共用同一触发器「真实工具立项」。「如无扩展」（真实工具永不立项）场景下仍保留：Mock 是工具调用/HITL/双链/编排四项能力的唯一演示载体，且项目定位（企业级平台工作台）要求这些特性可演示可验收。

---

## 六、批次分解（3d）

> **进展**：批1 ✅（2026-09-05 落码 + 验证通过：kb-ai-agent + kb-api 28 测试类 185 用例全绿）。批1 实现注记：① `SubAgentClientFactory` 接口化为 TaskTool 可测性设计（生产实现 = Config 内 Spec.name 缓存工厂，`ChatClient.Builder` 类型核验为嵌套接口）；② 落码前核验 N1 通过——`AuditTraceAdvisor.MODE_KEY` 为自由字符串、无白名单（仅 `"rag".equals(mode)` 特判 traceEntries，`'agent'` 落库零 DDL 零阻）；③ 批1 已注册 data-query / report-writer 两 Spec（D2 差异化模型），knowledge-searcher 批2 落；④ Mock 拆类双挂后 tool 链工具集等价（三 @Tool 全在）。

### 批1：编排骨架（1d）

| 序号 | 模块 | 文件/类 | 说明 |
|---|---|---|---|
| 1.1 | kb-ai-agent | `orchestration/SubAgentSpec.java` | record 描述子 |
| 1.2 | kb-ai-agent | `orchestration/SubAgentRegistry.java` | 注册表 + prompt 渲染 |
| 1.3 | kb-ai-agent | `orchestration/TaskTool.java` | 委派工具 + 超时 + 失败文本化 + recordToolCall |
| 1.4 | kb-ai-agent | `orchestration/AgentOrchestratorService.java` | 同步/流式服务 + toolContext 组装 |
| 1.5 | kb-ai-agent | `config/OrchestratorChatClientConfig.java` | 条件装配 orchestratorChatClient（复用 agentToolCallingAdvisor） |
| 1.6 | kb-api | `AgentController.java` | resolveMode 扩 'agent' + 分流 + ORCHESTRATOR_DISABLED 守卫 |
| 1.7 | kb-ai-agent | `tool/EnterpriseMockReadTools.java` + `EnterpriseMockWriteTools.java` | Mock 拆类（读/写分离），`ToolAgentChatClientConfig` defaultTools 双挂行为不变 |
| 1.8 | kb-api | `application-ai.yml`（或 infra 导入链既有落位） | `rag.orchestrator.*` 配置族（enabled / subagent-timeout-seconds） |

**单测**：SubAgentRegistry 路由与 prompt 渲染 / TaskTool 超时与失败语义 / toolContext 身份下传 / 开关关闭态 Bean 缺位与 400 / Mock 拆类后 tool 链装配等价回归（EnterpriseMockToolsTest 拆随）。
**验证**：`mvn -q --no-transfer-progress test -pl kb-ai-agent,kb-api -am`
**提交**：`feat(orchestrator): 批1 编排骨架——TaskTool 委派 + SubAgentRegistry + mode=agent 第三链`

### 批2：三子代理 + 指标 + 前端（1d）

| 序号 | 模块 | 文件/类 | 说明 |
|---|---|---|---|
| 2.1 | kb-ai-agent | `orchestration/KnowledgeSearchTools.java` | searchKnowledge/getDocument 薄委派（身份经 toolContext） |
| 2.2 | kb-ai-agent | `SubAgentRegistry` 装配 | 三子代理 Spec 注册（模型分工按 D2 定案） |
| 2.3 | kb-ai-core | `metrics/AiBusinessMetrics.java` | rag.orchestrator.* 指标族 |
| 2.4 | frontend | `stores/chat.ts` + `views/Chat.vue` + `api/index.ts` | mode 类型扩 'agent' + 切换第三选项 + 文案 |

**单测**：KnowledgeSearchTools 租户 fail-closed / 三子代理 Spec 装配断言 / 指标计数路径。
**验证**：`mvn -q --no-transfer-progress test -pl kb-ai-agent,kb-ai-core -am` + `npm run build`（frontend）
**提交**：`feat(orchestrator): 批2 三 Mock 子代理 + 指标族 + 前端编排入口`

### 批3：契约文档 + 演示验收（1d）

| 序号 | 产出 | 说明 |
|---|---|---|
| 3.1 | 11 章 §11.5.5 扩写 | 「后续演进」→「Multi-Agent 收窄骨架设计」：骨架设计 + 真实工具挂接契约（§七大纲）+ 11.2 链序表注记 |
| 3.2 | 07 卷簇⑤段 + 本文档 | 任务行进展回填 + 定案记录回填（D1-D6 裁决） |
| 3.3 | CLAUDE.md | 双链路架构行更新（三 mode）+ 多 ChatClient Bean 纪律第五 Bean |
| 3.4 | 演示任务集 10 例 + E2E 步骤 | 判定标准文档化，交付用户自测 |

**提交**：`docs(orchestrator): 批3 真实工具挂接契约 + 簇⑤收官文档三件套`

---

## 七、真实工具挂接契约（§11.5.5 回写大纲）

1. **SubAgentSpec 注册契约**：新增真实能力 = 新增一条 Spec（name/description/systemPrompt/toolCallbacks/chatModel/timeout），注册即被主 Agent prompt 自动纳入；已有子代理换真实实现 = Spec 的 toolCallbacks 换实现类，链路与主 Agent 零改动
2. **真实工具实现契约**：@Tool 签名与描述即模型接口契约；身份必经 ToolContext（ToolContextKeys.TENANT_ID/USER_ID/RETRIEVAL_CONTEXT）消费，租户 fail-closed 两层语义（入口守卫 + 有 ctx 无租户空结果）沿用；返回结构化 record（对齐 EnterpriseMockTools 契约先例）
3. **写操作/HITL 跨层升级路径**：当前 HITL 三段式在 tool 链单层；编排链下写工具的挂起-汇聚-审批-续跑需审批账本扩展「子代理委派上下文」字段 + TaskTool 结果携带 PENDING 语义上行——真实写工具立项时设计定稿
4. **usage 聚合计账路径**：子代理轻链不挂 TokenBudget（防双计）；升级形态 = TaskTool 执行后子调用 usage 累加回 RetrievalContext 口径，TokenBudgetAdvisor 流末一次计账
5. **并发子代理升级路径**：按 D5 核验结论登记
6. **原验收复活触发器**：真实 OA/ERP/DB 工具立项 → 升级回「任务完成率 >85%（真实工具基准集度量）」，与 5.4-A 跨链 auto 路由复活同步触发

---

## 八、验收与 E2E

- **簇⑤ DoD**（Phase 5 方案 §5.3）：代码 + 单测绿；验证通道通过（Mock 委派演示 E2E 通过率 ≥80% + 契约文档评审）；文档三件套回写；git 提交
- **演示任务集**（10 例，批3 交付全量；形态示例）：「检索公司差旅报销制度要点，查询 E1001 员工信息与年假余额，起草一份假期申请情况说明」——跨三子代理复合任务 / 两子代理 / 单子代理深度任务分层覆盖
- **判定标准**：① 委派对象正确（子代理选择与任务语义匹配）；② 委派参数合理（description 自包含可执行）；③ 最终答案综合子代理结果（非凭空作答）；④ 知识类内容有检索依据（主 Agent 不越权直答）。10 例中 ≥8 例全过 = 通过
- **E2E 步骤**：批3 按项目纪律交付（开关开启 → 前端切 agent 模式 → 复合任务 → TOOL_CALL 卡片观察委派 → 综合答案 → 审计行 mode='agent' + 指标计数核对），用户自测结果下轮回传

## 附：落码前核验点清单

| # | 核验项 | 方法 |
|---|---|---|
| N1 | AuditTraceAdvisor / Bad Case 查询端点对 mode 值的枚举假设（'agent' 落库与查询是否受阻） | 源码级 |
| N2 | `DefaultToolCallingManager` 同消息多 tool call 并行/串行形态（D5） | 源码级 |
| N3 | McpKnowledgeTools.search 的身份消费签名（ToolContext 形态），定知识检索子代理复用粒度 | 源码级 |
| N4 | 子代理调用的 observation 传播（trace 合树可行性） | 源码级 + 本地观测验证 |
| N5 | ChatClient 按 Spec 缓存的安全性（工具回调无状态假设） | 源码级 |
