# Phase5簇⑥ 产品化收尾实施方案（批次推进版）

> **版本**：v1.0（提案待拍板）· **日期**：2026-09-13 · **工时**：~4d（批1 1d + 批2 1.5d + 批3 1.5d）· **模块跨度**：kb-ai-agent（dingtalk/ a2a/ 新包）/ kb-api（A2A 端点 + SecurityConfig）/ kb-ai-core（指标）/ docs（delivery 五件 + 18 章 + 11 章）
> **性质**：Phase5簇⑥ 落码执行基线（现状勘察 + 架构设计 + 待定案决策点）。复审定案出处：`docs/project-optimization/Phase 5 复审与规划方案（调研实证版）.md` §二 5.4/5.5/5.12 · §四 N3 · §三（全项目收口判据）· §五 簇⑥ 行；批次进展回填 07 卷 Phase5簇⑥ 段。
> **既有推进**（07 卷已留痕）：E2E 体验批1-3（流式恢复 / toolCalls 归档回显 / 三链路进度推送）+ 热修一 + 补强一至四（含 kb_session.mode 列）已于 2026-09-07/08 全部收官——**本方案只覆盖剩余主体五件**：钉钉机器人（5.12）+ A2A（N3）+ 5.4/5.5 归档 + delivery 增量 + 全阶段验收复盘。
> **官方路径核验（2026-09-13，网络调研）**：
> ① 钉钉 Stream SDK = 官方 [open-dingtalk/dingtalk-stream-sdk-java](https://github.com/open-dingtalk/dingtalk-stream-sdk-java)（Maven `com.dingtalk.open:dingtalk-stream-sdk-java`，[Maven Central 最新版 2025-10-22](https://mvnrepository.com/artifact/com.dingtalk.open)，多模块聚合，独立 SDK 无 Spring 依赖）；接入形态 = `OpenDingTalkStreamClientBuilder`（Client ID/Secret）→ WebSocket **出站长连接**（[无需公网回调](https://open.dingtalk.com/document/resourcedownload/introduction-to-stream-mode)）→ `registerCallbackListener` 注册 `ChatbotListener` 收 @ 消息（`ChatbotMessage`）→ 经 `sessionWebhook` 直接 POST markdown 回复（[机器人接收消息](https://open.dingtalk.com/document/dingstart/robot-receive-message)；[quick-start 示例](https://github.com/open-dingtalk/dingtalk-stream-sdk-java-quick-start)）。前置 = 钉钉开发者后台创建**企业内部应用机器人**（消息接收模式选 Stream）。
> ② A2A = 官方 [a2aproject/a2a-java](https://github.com/a2aproject/a2a-java)（`org.a2aproject.sdk:a2a-java-sdk-reference-jsonrpc`，Java 17+，`AgentExecutor` 接口 + v1.0/v0.3 兼容层）——但**无官方 Spring Boot 集成**（官方集成仅 Quarkus 参考实现 + Jakarta EE 社区件 a2a-jakarta；[文档站](https://a2aproject.github.io/a2a-java/)）。Spring 生态参照 = [官方博客 Agentic Patterns Part 5：A2A Integration](https://spring.io/blog/2026/01/29/spring-ai-agentic-patterns-a2a-integration)（Agent Card 发现 + 互操作模式）。⇒ 本项目 Boot 4.1 + Jackson 3（tools.jackson）形态下 SDK 直引存在未实证面，构成决策点 D1。
> **分支纪律**：纯文档批3 可直推 main；批1/2 代码批沿 Phase5簇④⑤先例拟分支 `phase5-cluster6-productization`（开工时按 main 状态定，亦可直推——开工时定）。

---

## 一、决策点选项集（裁决留档，推荐项置首位；待用户拍板后回填定案记录）

### D1 A2A 实现形态

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **自研协议适配层最小集**：`GET /.well-known/agent-card.json`（Agent Card 静态发布）+ `POST /a2a`（JSON-RPC 2.0，`message/send` 同步应答 v1.0 + `tasks/send` v0.3 双方法名兼容），Task/Artifact 按 spec 组装，能力位只声明同步（streaming/pushNotification 不声明） | 协议面小（两方法同步应答）；全链复用 Spring MVC + JWT resource server + 审计/限流/护栏（SDK 反需桥接 `AgentExecutor`）；避开 SDK 与 Boot 4.1/Jackson 3 共存的未实证面；**验收判据「标准 A2A Client 调用通过」不受实现形态影响**（用官方 client 验证合规性）；升级路径登记：真实 A2A 生态流量出现时迁 a2a-java SDK（桥接点 = A2aAgentService 单类，已隔离） |
| B | a2a-java SDK 直引（批2 首步 spike：引 `a2a-java-sdk-reference-jsonrpc` 起上下文，核验 Boot 4.1 装配 + Jackson 2/3 classpath 共存 + 传输挂载形态），过则用 SDK，败则回落 A | 官方 SDK 协议覆盖全（gRPC/REST/流式/任务生命周期）且后续零维护；但无 Boot 集成先例、Quarkus 向生态、多 1d spike 成本与不确定性；最小形态用不到其多数能力 |
| C | A2A 移出挂起（Phase 5 方案 §五 预留压缩项，-2d） | Phase5簇⑥ 工时收紧时的正当选项；但 N3 为用户定案纳入项，且 A2A 是全项目唯一前瞻协议落点，建议保留 |

### D2 钉钉机器人身份映射

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **服务账号单租户绑定**：`rag.dingtalk.tenant-id` 显式配置绑定唯一租户；开关键在场且 tenant-id 空白 → **启动失败**（fail-closed，防裸租户进检索链）；`ChatbotMessage.senderStaffId` 透传 userId（审计/护栏用户维度仍可用，缺失回落 `dingtalk-anonymous`） | 单租户企业库场景（5.5 同判据）；零 DDL 零映射表；身份纪律对齐 `McpIdentityGuard`（tenantId 缺失即 `IDENTITY_INCOMPLETE` 语义，形态升级为启动期拦截——Stream 客户端无请求线程可抛 4xx，拦截点前移） |
| B | 钉钉用户 → 租户映射表（DB 或配置）：多租户群部署 | 本期无真实多租户群需求（5.5 同款「不预迁移」纪律）；引入映射治理面，超最小形态 |
| C | 逐用户 JWT 模拟（钉钉身份换签 Casdoor token） | 需后端持签发密钥模拟用户，安全面不可接受 |

### D3 钉钉对话链路与回复形态

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **mode 固定 rag + 溯源附录**：复用 `RagChatService.chatRag`（`ChatClient.call()` 同步阻塞式，全护栏/审计/记忆链零减配）；回复 = answer 正文 markdown + 尾部溯源列表（citation title/source 映射 [ref-N]；落码核验同步路径 RetrievalContext 流末快照可读性，不可读则降级仅 [ref-N] 编号并登记） | 知识问答场景语义正确；tool/agent 链 HITL 三段式在群聊无审批载体（Redis 账本 10 分钟 TTL 无人消费必超时）+ agent 链 1-2 分钟长任务群聊体验差；溯源是本项目产品身份（[ref-N] 锚定 + 空证据拒答），对外触达不减配 |
| B | mode 可配置（rag\|agent） | 群聊无人审批 HITL 写工具 → 挂起后超时失败，演示价值负；如后续真实需要经独立会话跳转前端审批，登记升级路径即可 |
| C | 纯文本回复无溯源 | 丢失产品身份特性；钉钉 markdown 卡片原生支持，无成本理由 |

### D4 全阶段验收复盘归档落点

| 选项 | 形态 | 依据 |
|---|---|---|
| **A（推荐）** | **18 章新增 §18.6「全阶段验收复盘矩阵」**：按 阶段（Phase 1-5 + 优化冲刺/安全专项）× 簇/专项 登记「验收判据 → 实测读数 → 结论 ✅/挂起登记」，读数从各进度卷汇总回填（LT1 压测/门禁退出码/多跳 AC/E2E 通过率等既有事实，不新跑）；文首版本史递增；`project-implement/README.md` 索引同步（顺手补齐已落后的 18 章 v2.65/v2.67 两个修订位登记） | 18 章 = 交付验收标准本体（勘察确认现有 §18.1-18.5 按维度组织、无按 Phase 复盘节，L82 后直入附录——§18.6 落点干净）；Phase 5 方案 §三 定案「全阶段验收标准复盘归档（18 章联动）」原文对应 |
| B | 另立独立复盘总集文档（project-optimization 下新文档） | 与 18 章定位重叠、双源漂移；复盘类文档已有先例（Phase 1-3 复盘）但那是「优化规划」入口，验收复盘归验收章 |

> **定案记录（用户拍板后回填此处）**：D1=_ · D2=_ · D3=_ · D4=_

---

## 二、Phase5簇⑥ 边界与不可破纪律

### 2.1 边界（Phase 5 方案定案）

1. **5.12 收窄**：钉钉**群机器人最小形态**（企业内部应用机器人 + Stream 模式）；企微完整集成、钉钉组织管理/审批流配套**不做**（触发 = 真实组织需求，登记挂起）；
2. **N3 最小形态**：KB Agent Card + 任务端点包装 rag 链对话能力；A2A 的流式（tasks/sendSubscribe）、推送通知、任务生命周期轮询（tasks/get/cancel）、多 Agent 编排消费**不做**（能力位不声明，升级路径登记）；gRPC/REST 传输不做（只 JSON-RPC）；
3. **5.4/5.5 只归档不实现**：设计稿归档登记 + 触发条件显式登记（5.4-A 跨链 mode=auto / 5.4-B 复杂度三级路由 / 5.5 多知识库动态路由），零代码；
4. **文档三件套实为五件**（docs/delivery/：README / API 文档 / 用户使用手册 / 运维手册 / 生产部署实操手册）——增量更新覆盖 Phase 5 全部新端点/新基建，不重写既有 Phase 4 内容；
5. **全阶段验收复盘**：读数汇总回填，不新跑评估（Phase5簇② 门禁基线 md1-final-3 / LT1 / 多跳 AC 等均为既有实测事实）。

### 2.2 不可破纪律

1. **敏感词交付纪律（红线）**：方案/代码/配置/文档/E2E 步骤零字面攻击载荷；
2. **开关缺省关 + 关闭态零变化**：`rag.dingtalk.enabled` / `rag.a2a.enabled` 缺省 false，`@ConditionalOnProperty` 整族条件装配（关闭态 Bean 缺位，三链与既有端点逐字节不变）；A2A 端点关闭态不存在（404 语义自然缺位）而非 403 暴露路径；
3. **身份 fail-closed**：钉钉 tenant-id 缺失启动失败（D2-A）；A2A 走既有 JWT bearer（`/api/**` authenticated 通道或显式 matcher，`anyRequest().denyAll()` 兜底纪律不破）；Casdoor token 无标准 scope claim 现状下，A2A scope 治理同 MCP 形态（端点层 authenticated + 可选 `rag.a2a.scope.required` 二次收敛，默认空仅租户纪律）；
4. **钉钉密钥走 env**：Client ID/Secret 经 `RAG_DINGTALK_CLIENT_ID/SECRET` 注入，yml 零字面；`infra/.env.example` Secrets 模板同步补行（不填真值）；
5. **复用对话链语义不减配**：护栏（输入消毒/注入拦截/输出黑名单/配额限流）/审计/租户隔离/记忆对钉钉与 A2A 入口同样生效（复用即继承，不旁路）；
6. **模块纪律**：服务域（DingTalkStreamClient/DingTalkChatService/A2aAgentService/守卫/审计/限流）落 kb-ai-agent 新包 `dingtalk/` `a2a/`（对齐 MCP 四件落位先例）；HTTP 端点（A2A Controller + Agent Card）落 kb-api；kb-api 聚合禁反向依赖不破；多 ChatClient Bean 注入显式 `@Qualifier`；Advisor 链序零改动；
7. **新增依赖两步走**：父 POM dependencyManagement 预埋（既有纪律）+ 单模块引入；批1 首步 `dependency:tree` 核验 dingtalk-stream-sdk-java 传递依赖（gson/okhttp 族）与 Boot 4.1 共存，冲突即 exclusions 处理并留档；
8. **指标零租户标签 + 独立 Counter 范式**：`rag.dingtalk.*` / `rag.a2a.*` 照 `rag.mcp.*`（`AiBusinessMetrics` 模式：多态拆独立 Counter，经 record 收口方法，未知操作不计）；
9. **簇 tag 规约**：本簇所有产出（代码注释/文档/提交信息）一律 `Phase5簇⑥` 前缀，禁裸「簇⑥」。

---

## 三、现状基线与复用落点（勘察 2026-09-13）

### 3.1 MCP 四件模式（钉钉/A2A 的同构母本，kb-ai-agent/mcp/）

| 复用对象 | 现状形态 | 钉钉/A2A 承接 |
|---|---|---|
| `McpIdentityGuard` | JWT（owner→tenantId / sub→userId）→ `new RetrievalContext()` 参数链；tenantId 空白抛 `IDENTITY_INCOMPLETE`；scope 二次收敛 `rag.mcp.scope.required`（默认空） | A2A 同构（JWT bearer 在场）；钉钉无 JWT → D2-A 启动期绑定替代 |
| `McpRateLimiter` | 独立桶 `rag:ratelimit:mcp:{tenantId}` + fail-open（可用性管控非安全边界） | 同款 `rag:ratelimit:dingtalk:{tenantId}` / `rag:ratelimit:a2a:{tenantId}` |
| `McpAuditRecorder` | 结构化日志恒开 + DB 轻行可开（`kb_audit_log` mode="mcp" 最小投影，PII 脱敏，异步旁路容错） | 同款 mode="dingtalk" / mode="a2a"（轻量审计形态，区别于对话链 AuditTraceAdvisor 全链快照——钉钉/A2A 委托 chatRag 后对话链审计自然在场，轻行是入口域补充） |
| `McpKnowledgeTools` | 三件套直调/全链复用，`mcp-` 会话前缀 | 会话前缀先例：`dingtalk-{conversationId}` / `a2a-{contextId}`（记忆域隔离） |

### 3.2 对话链复用

- **同步路径**：`RagChatService.chatRag` = `ChatClient.prompt().call().content()` 阻塞式（kb-ai-core，:40-49）——钉钉监听线程直接调用零改造；A2A `message/send` 同步应答同用；
- **入口参数链**：Controller 侧 `newRetrievalContext()` 纯实例 + `CONTEXT_KEY` 参数链（AgentController :371-379）——钉钉/A2A 服务侧同构自建；
- **溯源快照**：SSE 流经 TRACE 帧推 citations（ctx 流末快照）；**同步路径 citations 是否可直读 ctx 待落码核验**（D3-A 降级预案已登记）；
- **会话记忆**：`CONVERSATION_ID` advisor 参数 + `agentChatMemory` Redis 仓库（窗口 20）——前缀会话即自然多轮（钉钉群 conversationId 天然会话键）。

### 3.3 SecurityConfig 与端点现状

- `anyRequest().denyAll()` 兜底 + specific 先于 general（SecurityConfig :66-91）；
- **钉钉 Stream = 出站长连接，无入站端点**——SecurityConfig / nginx / ECS 安全组**零改动**（这是 Stream 模式相对 webhook 回调的决定性优势）；
- A2A 两端点需显式接入：方案 = `/a2a` 与 `/.well-known/agent-card.json` 加 authenticated matcher（对齐 `/mcp` 先例：`requestMatchers("/mcp").authenticated()`）——Agent Card 协议语义为公开发现，但本项目单租户内部形态下 Card 含内网端点信息，**跟随 `/mcp` 同款 authenticated**（登记差异注记：标准 A2A Client 支持凭据发现后带 token 拉 Card，验收步骤覆盖）；
- CORS 允许头仅 Authorization/Content-Type/Accept——A2A JSON-RPC POST 仅用 Content-Type，零影响。

### 3.4 配置与开关范式（application-ai.yml `rag:` 树）

- `@ConditionalOnProperty(prefix="rag.X", name="enabled", havingValue="true")` 整族装配 + 入口 `ObjectProvider` 守卫显式拒绝（OrchestratorChatClientConfig / CacheCheckAdvisor 先例）；
- 钉钉客户端另需生命周期挂接：`SmartLifecycle`（启动时建连、停机时断连，@ConditionalOnProperty 同款门控）。

### 3.5 delivery 五件缺口基线（勘察实测）

| 文档 | 现版本 | 主要缺位 |
|---|---|---|
| API文档.md | 1.1（2026-08-22） | `mode` 枚举无 agent；graph backfill 端点全缺；sessions 响应无 mode 字段；stats overview 载荷无 Bad Case 双计数；§6 MCP 后无钉钉/A2A |
| 用户使用手册.md | 1.0（2026-08-22） | §3.1 仍「两种模式」（缺编排链交互：委派卡片/进度行）；会话链路徽标（知识/工具/编排三色）；§7 运维中心缺反馈导出入口与 ROLE_ADMIN/SUPER_ADMIN 三级权限差异；钉钉入口（批1 后补） |
| 运维手册.md | 1.2（2026-09-12） | Neo4j 第二台 ECS 资产/拓扑行；钉钉/A2A 配置族与排障；词表 DB 单轨运维细节核对 |
| 生产部署实操手册.md | 变更史已至 1.10（文首标注滞后在 1.6） | **Neo4j 第二台 ECS 部署章全缺**（现仅驱动装配/排障 2 处提及）；版本标注对齐；钉钉/A2A env 表行 |
| README.md | Phase4簇⑦ 形态 | 索引描述 + 五件覆盖状态 + Phase 5 能力总述 |

### 3.6 18 章落点（勘察实测）

- 现结构：§18.1 功能完整性 / §18.2 性能 / §18.3 工程质量 / §18.4 压测基线 / §18.5 GraphRAG 验收口径（L72-82），其后直入合订附录——**§18.6 落点干净**；
- Phase 5 各簇登记现状：Phase5簇① LT1 已回填 §18.4；Phase5簇④ §18.5 有口径无实测回填列；Phase5簇③ 仅前瞻注记；Phase5簇②（GLM 门禁重锚）/ Phase5簇⑤（演示 E2E 10/10）未登记——复盘矩阵一并清偿；
- README 索引 18 章行落后两个修订位（v2.65/v2.67 未同步）——顺手补齐。

---

## 四、架构设计

### 4.1 钉钉群机器人（kb-ai-agent 新包 `dingtalk/`，批1）

```
钉钉开放平台 ──WebSocket 出站长连接──▶ DingTalkStreamClient（SmartLifecycle，条件装配）
                                          │ ChatbotListener（@ 消息，ChatbotMessage）
                                          ▼
                                     DingTalkChatService
                                       ├─ 身份：tenant-id 启动期绑定（D2-A）+ senderStaffId→userId
                                       ├─ 限流：McpRateLimiter 同款独立桶（fail-open）
                                       ├─ 委派：RagChatService.chatRag（同步阻塞，全护栏/审计/记忆链）
                                       │         CONVERSATION_ID = "dingtalk-{conversationId}"
                                       ├─ 溯源：ctx 流末快照 citations → markdown 尾部列表（D3-A）
                                       ├─ 回复：sessionWebhook POST markdown（title=问题摘要，text=答案+溯源）
                                       ├─ 审计：McpAuditRecorder 同款轻行（mode="dingtalk"）
                                       └─ 指标：rag.dingtalk.{message,replied,rate-limited,error}
```

- **组件清单**：`DingTalkProperties`（@ConfigurationProperties `rag.dingtalk.*`）· `DingTalkStreamConfig`（条件装配 + SmartLifecycle Bean）· `DingTalkStreamClient`（builder 封装 + 重连语义交 SDK）· `DingTalkChatService`（编排中枢）· `DingTalkReplyClient`（sessionWebhook POST，独立小客户端便于单测）；
- **异步语义**：ChatbotListener 回调线程不做长阻塞——chatRag 同步调用放虚拟线程执行器（父 POM 虚拟线程使能，对齐 ETL 异步派发先例），监听回调即提交即返回（钉钉侧无应答压力，回复经 sessionWebhook 异步推回）；
- **超时**：chatRag 调用包一层超时保护（缺省 120s，`rag.dingtalk.chat-timeout-seconds`；超时回复超时话术不静默）；
- **降级**：sessionWebhook 回复失败仅计数+warn（消息已处理，不可重放语义清楚）。

### 4.2 A2A 最小形态（kb-ai-agent `a2a/` + kb-api 端点，批2，D1-A 形态）

```
A2A Client ──GET /.well-known/agent-card.json──▶ AgentCardController（kb-api，静态 Card）
         ──POST /a2a（JSON-RPC message/send v1.0 / tasks/send v0.3）──▶ A2aController（kb-api）
                                          │ JWT bearer（authenticated matcher，/mcp 同款）
                                          ▼
                                     A2aAgentService（kb-ai-agent）
                                       ├─ 身份：McpIdentityGuard 同构（owner→tenantId fail-closed）
                                       ├─ 限流/审计/指标：rag:ratelimit:a2a:{tenant} / mode="a2a" / rag.a2a.*
                                       ├─ 委派：chatRag 同步（CONVERSATION_ID = "a2a-{contextId}"）
                                       └─ 应答组装：Task(state=completed) + Artifact(Part text=answer)
                                          同步 JSON-RPC response（错误码：JSON-RPC 标准 + 协议错误对象）
```

- **Agent Card**（v1.0 spec 字段落码前源码级核验 a2a-protocol.org）：name/description/url(=部署端点)/version/capabilities(仅声明非流式)/defaultInputOutputModes(text)/skills(kb_qa 知识库问答)/securitySchemes(bearer)+security 引用；
- **协议方法支持面**：`message/send`（v1.0）与 `tasks/send`（v0.3）双方法名等价处理（最小兼容）；`message/stream`、`tasks/get`、`tasks/cancel` 等返回 JSON-RPC method-not-found 标准错误（能力位未声明，合规拒答）；
- **落码前核验清单（批2 首步）**：Agent Card v1.0 确切 schema 字段名、Task/Artifact/Part 结构、错误码形态——以 spec 与官方 client（a2a-python/a2a-js）源码为准，不凭记忆落码；
- **升级路径登记**：迁 a2a-java SDK 时桥接点 = `A2aAgentService` 单类（Controller/Card 零改动）。

### 4.3 指标族（kb-ai-core AiBusinessMetrics）

- `rag.dingtalk.message`（收到 @ 消息）/ `rag.dingtalk.replied` / `rag.dingtalk.rate-limited` / `rag.dingtalk.error` + `rag.dingtalk.chat.duration`（Timer，p95/p99）；
- `rag.a2a.request` / `rag.a2a.rate-limited` / `rag.a2a.error` + `rag.a2a.chat.duration`；
- 全部零标签（防基数）、record 收口方法、description 带批次注记。

### 4.4 5.4/5.5 归档登记内容（批3，纯文档）

- **5.4-A 跨链 mode=auto**：设计稿归档锚点 = 11 章 §11.4（意图路由节）追加「归档登记」小节——设计要点（路由决策面：rag↔tool↔agent 三链自适应）+ 触发条件（真实工具落地 + 跨链混合流量）+ 复活路径（QueryRoutingAdvisor 分类位扩展）；
- **5.4-B 复杂度三级路由**：不启动项登记（依赖轻量模型档位引入，3.2 定案双模型下形同虚设判据维持）；
- **5.5 多知识库动态路由**：设计稿登记（领域独立 VectorStore + 路由选择原设计）+ 触发条件（第二个真实知识库接入需求）；
- 登记载体：11 章归档小节 + Phase 5 方案对应行补「已归档 ✅」标注。

---

## 五、批次分解（4d）

### 批1：钉钉群机器人（1d）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 依赖引入：父 POM dependencyManagement 预埋 `com.dingtalk.open:dingtalk-stream-sdk-java`（钉最新 release）+ kb-ai-agent 引入；`dependency:tree` 核验传递依赖共存 | 树干净或 exclusions 留档 |
| 2 | `DingTalkProperties` + `DingTalkStreamConfig`（条件装配 + tenant-id fail-closed 启动校验 + SmartLifecycle）；application-ai.yml 配置族（缺省关 + env 占位）；`infra/.env.example` 补行 | 关闭态零 Bean；开启态缺 tenant-id 启动失败（单测钉） |
| 3 | `DingTalkStreamClient` + `DingTalkChatService` + `DingTalkReplyClient`（4.1 全链：身份/限流/chatRag/溯源附录/markdown 回复/审计/超时/异步提交） | 单测：监听器→服务编排（桩 ChatModel）/markdown 组装/溯源降级/超时话术/限流与审计旁路 |
| 4 | 指标族 + 单测收口；07 卷批1 行 + 00 卷状态行回写；用户侧前置项登记（钉钉后台建应用三步：创建企业内部应用→添加机器人（Stream 模式）→群内启用；取 AppKey/Secret 配 env） | `mvn -q --no-transfer-progress test -pl kb-ai-agent -am` 绿 |

### 批2：A2A 最小形态（1.5d，D1-A）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 协议核验：a2a-protocol.org v1.0 spec（Agent Card schema / Task/Artifact/Part / JSON-RPC 方法面）+ 官方 client 源码形态 | 核验结论回写本方案 §4.2 |
| 2 | `A2aAgentService`（kb-ai-agent：身份守卫/限流/审计/chatRag 委派/应答组装） | 单测：身份 fail-closed / 应答组装 / 方法不支持错误 / 限流 |
| 3 | `A2aController` + `AgentCardController`（kb-api）+ SecurityConfig matcher（authenticated）+ application-ai.yml `rag.a2a.*` | 关闭态端点缺位；单测含 JSON-RPC 解析与错误码 |
| 4 | 指标族；07 卷批2 行 + 00 卷回写；E2E 脚本（curl Agent Card + message/send + 官方 Python client 步骤）交付 | `mvn -q --no-transfer-progress test -pl kb-api,kb-ai-agent -am` 绿 |

### 批3：文档收口批（1.5d，纯文档零代码）

| 步骤 | 内容 | 验收 |
|---|---|---|
| 1 | 5.4/5.5 归档登记（11 章 §11.4 归档小节 + Phase 5 方案行标注，4.4 大纲） | 两章版本递增 + 修订注记 |
| 2 | delivery 五件增量（3.5 缺口表逐项；批1/2 新端点钉钉/A2A 并入；实操手册补 Neo4j 第二台 ECS 部署章 + 版本标注对齐；README 索引更新） | 五件版本递增；缺口表逐项销账 |
| 3 | 18 章 §18.6 全阶段验收复盘矩阵（D4-A：阶段×簇 判据→读数→结论；含 Phase5簇②③⑤ 既有点位清偿）+ README 索引同步（补 v2.65/v2.67） | 矩阵覆盖 Phase 1-5 + 两专项全簇 |
| 4 | CLAUDE.md 架构事实同步（钉钉/A2A 两行 + 当前阶段行收口）+ 07 卷批3 行 + 00 卷收官状态行 | CLAUDE.md ≤24KB 纪律 |

### 机动（0.5d）

批1/2 E2E 回传缺陷热修缓冲。

---

## 六、验收与 E2E（用户侧执行清单）

### 6.1 DoD（每批）

1. 代码 + 单测绿（涉及模块 `mvn -q --no-transfer-progress test -am`）；
2. 关闭态回归：`RAG_DINGTALK_ENABLED`/`RAG_A2A_ENABLED` 不设 → 启动零变化（三链/既有端点逐字节不变）；
3. 一功能一提交（代码 + 文档同批），簇 tag 带 Phase5簇⑥ 前缀。

### 6.2 批1 E2E：钉钉机器人 @ 触发问答

> 前置（用户侧一次性）：钉钉开发者后台创建企业内部应用 → 添加机器人能力（消息接收模式选 **Stream 模式**）→ 发布应用 → 目标群添加机器人 → 取 AppKey/AppSecret 配入 `RAG_DINGTALK_CLIENT_ID/SECRET` + `RAG_DINGTALK_TENANT_ID`（本租户 ID）+ `RAG_DINGTALK_ENABLED=true` → 重启应用（日志确认 Stream 连接建立）。

1. 群内 @ 机器人知识库问题 → markdown 卡片回复，正文含 [ref-N] 锚定 + 尾部溯源列表；
2. 同群追问（依赖前缀会话记忆）→ 回复体现上下文延续；
3. @ 一条注入族系样本（样本 ID 形态，见安全专项语料）→ 拒答话术不泄露；
4. 连续 @ 超限（若限流档设低可观察）→ 限流话术；`kb_audit_log` 查 mode='dingtalk' 行（query 脱敏形态）；Grafana/actuator 指标 `rag.dingtalk.*` 计数可读；
5. 溯源降级核验（若批1 实现走了降级形态）：回复仅 [ref-N] 编号，登记读数。

### 6.3 批2 E2E：标准 A2A Client 调用

1. `curl -H "Authorization: Bearer <JWT>" https://<host>/.well-known/agent-card.json` → Card JSON（name/capabilities/skills/securitySchemes 完整）；
2. `curl -X POST .../a2a`（JSON-RPC `message/send`，params.message.parts[0].text=知识库问题）→ 同步 response：result.task.state=completed + artifacts[0].parts[0].text 含答案；
3. `tasks/send`（v0.3 方法名）同题 → 等价应答；
4. 无效方法（`tasks/get`）→ JSON-RPC method-not-found 标准错误；
5. 无 token → 401；错租户 token → 检索空结果不泄露；
6. （可选加强）官方 a2a-python client `A2AClient.send_message` 一轮——通过即「标准 A2A Client 调用通过」判据闭环（D1-A 形态的合规性验证通道）。

### 6.4 批3 E2E：文档评审

1. delivery 五件通读：三模式对话/链路徽标/反馈导出/权限分级/钉钉入口/Neo4j 部署章/backfill 端点逐一在册；
2. 18 章 §18.6 复盘矩阵抽查三行（任选）对照进度卷读数一致；
3. CLAUDE.md 当前阶段行与实际状态一致。

---

## 七、风险与预案登记

| # | 风险 | 预案 |
|---|---|---|
| R1 | dingtalk-stream-sdk-java 传递依赖（gson/okhttp）与 Boot 4.1 冲突 | 批1 步骤1 dependency:tree 核验；冲突 exclusions + 留档；极端不兼容则回落官方 raw WebSocket 协议自实现（Stream 协议公开）或 webhook 形态论证入档（5.12 原文预留） |
| R2 | 钉钉 Stream 模式企业内部应用机器人权限面变更（平台侧 2026 演进） | 批1 落码前以官方文档当期形态为准（本方案核验 2026-09-13）；群自定义机器人 outgoing webhook 回落论证预留 |
| R3 | 同步路径 citations 快照不可直读（D3 溯源降级） | 降级仅 [ref-N] 编号 + 登记读数；升级路径 = rag 链同步响应补 citations 字段（kb-api 小改，挂后续需求） |
| R4 | A2A v1.0 spec 字段形态与记忆偏差 | 批2 步骤1 spec + 官方 client 源码核验前置，不凭记忆落码（项目落码约束既有纪律） |
| R5 | 18.6 复盘矩阵读数汇总工作量大（六阶段全簇） | 读数均为进度卷既有事实，按卷索引行定位提取（00 卷状态行 + 各阶段完成记录），不新跑不重算 |
| R6 | 钉钉 E2E 依赖用户侧后台操作（应用创建权限） | 前置三步在批1 交付时即列明；若用户侧暂无管理员权限，批1 机器侧完成 + E2E 挂用户侧窗口（不阻塞批2/3 推进） |
