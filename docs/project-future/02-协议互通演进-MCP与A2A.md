# 02 · 协议互通演进（MCP 与 A2A）

> 状态：设计预案（未立项）· 基线：MCP Server（Phase4簇⑤ 4.10）+ A2A v1.0 最小形态（Phase5簇⑥ 批2）+ 钉钉群机器人（批1）· 触发条件：外部 Agent 生态对接 / 工具以 MCP Server 形态供给

## 1. 现状盘点

| 通道 | 方向 | 现状 | 成熟度 |
|---|---|---|---|
| MCP **Server** | 入站（我们供工具） | Streamable HTTP `/mcp` 三工具（search/get_document/ask）+ JWT/scope/限流/审计治理 | ✅ 生产态（DT 系列与集成指南在档） |
| A2A **Server** | 入站（我们供 Agent 服务） | 自研 v1.0 协议层：Card 发现 + `SendMessage` 同步应答；官方 a2a-sdk 1.1.2 互操作验证 | ✅ 最小形态（v1.0 单版本；流式/任务查询/推送未实现，Card 如实声明双 false） |
| A2A **Client** | 出站（我们调外部 Agent） | **缺位**——无出站联邦能力 | ❌ |
| MCP **Host/Client** | 出站（我们消费外部工具） | **缺位**——工具仅 Java 直连形态（Mock） | ❌ |
| IM | 入站 | 钉钉 Stream 群机器人（`dingtalk/` 服务域 + WebSocket 出站长连接） | ✅ 生产态（单 IM） |

## 2. 差距与目标形态

```
                入站（现状 ✅）                    出站（本分册目标）
  外部 MCP Host ──▶ /mcp 三工具          编排链/工具链 ──▶ MCP Client 消费外部 MCP Server
  外部 A2A Client ─▶ /a2a SendMessage     编排链 ──▶ A2A Client 调外部专家 Agent（联邦委派）
  钉钉群 ──────────▶ Stream 机器人         （IM 出站 = 主动推送通知，低优）
```

演进后系统同时具备「**被集成的服务**」与「**集成他人的 Agent**」双向能力——知识库不只是工具提供方，编排链可把外部 Agent 当作一类子代理。

## 3. 市面主流技术对照（2026）

| 方向 | 主流形态 | 对照结论 |
|---|---|---|
| MCP | 事实标准：官方/社区 Server 生态（DB/搜索/浏览器/代码库等）；2025 起 Streamable HTTP 定型 + OAuth2 授权规范（RFC 9728 资源服务器语义）；各大框架与 IDE 原生支持 | 出站消费 = 用 Spring AI MCP client starter 对接任意外部 Server；入站已就绪，补 OAuth2 授权码流（当前 JWT bearer 足够内网形态） |
| A2A | Linux Foundation 治理；v1.0 定稿（PascalCase 方法族、Card 规范）；生态以 Google ADK/LangGraph 侧 Agent 联邦为主流用例 | 出站 = 官方 a2a-java SDK（若其运行时绑定已解 Spring 兼容）或复用自研协议层同构实现 Client（JSON-RPC 形态简单，自研成本可控——spike 判负仅针对 Server 侧 SDK 绑定） |
| IM 机器人 | 钉钉/企微/飞书三足；均支持回调与长连接双模式 | 泛化 IM Channel SPI（`DingTalkChatService` 模式参数化），按需加企微/飞书 adapter |
| Agent 互操作安全 | 联邦场景的跨方身份（OAuth2/on-behalf-of）、工具越权、数据出站治理为共识难点 | 出站通道纳入既有护栏/审计/配额域（通道级出站白名单 + 审计 mode 扩展） |

## 4. 设计方案

### 4.1 MCP Host 化（出站消费外部工具）——与 01 分册互补

- **动机**：工具供给的主流形态已是 MCP Server——接入一个外部 MCP Server 即获得一组现成工具（数据库/搜索/内部 API），无需逐个手写适配器；
- **形态**：Spring AI MCP client 接入（`spring-ai-starter-mcp-client`），外部 Server 连接配置化（`rag.tools.mcp.servers[]`：端点/凭证/启停）；外部工具经 `ToolCallbackProvider` 并入 tool/agent 链 `defaultTools`——对模型而言与本地工具无差别；
- **治理**：外部工具同入 01 分册授权矩阵（工具可见性管控）；出站调用计配额与审计（tool_calls 快照标注来源 `mcp:external`）；**写入类外部工具默认拒绝**（白名单显式开启 + 走 HITL）；
- **与编排链**：`SubAgentSpec` 可指定子代理工具集含外部 MCP 工具（如 data-query 子代理挂外部 DB MCP Server）。

### 4.2 A2A 出站联邦（编排链委派外部专家 Agent）

- **动机**：企业内多 Agent 并存（HR Agent/法务 Agent/数据分析 Agent）后，「知识问答 Agent」可经 A2A 把非知识域任务委派出去——编排链 SubAgentRegistry 新增一类「远程子代理」；
- **形态**：`A2aRemoteSubAgent`——实现与本地子代理同构的委派接口（TaskTool 委派预算/超时/失败文本化回流全部复用），内部经 A2A Client（自研 JSON-RPC 同构实现）调远端 `SendMessage`；远端 Card 预取缓存 + 健康探测；
- **身份**：出站携带机器凭证（OAuth2 client credentials，Casdoor 机器账号签发）；远端若是外部组织的 Agent，数据出站经**内容审查守卫**（敏感词表同源 + 出站内容白名单域）；
- **超时与非打断**：远端同步应答 10-30s 量级——委派超时与既有 TaskTool 非打断式 cancel 语义对齐（弃任务不等结果）。

### 4.3 A2A Server 面扩容（入站能力补全）

按外部 Client 需求逐项开启（每项独立小批）：

| 能力 | spec 方法 | 设计要点 |
|---|---|---|
| 流式应答 | `SendStreamingMessage` | SSE 事件流（复用 rag 链既有流式管线：token→artifact-update 增量事件；护栏增量放行/REPLACE 追回语义映射）；Card `streaming: true` |
| 任务生命周期 | `GetTask` / `CancelTask` / `ListTasks` | 需任务持久化（当前同步应答无任务态存储）——Redis 任务表 TTL + 与 contextId 会话域关联 |
| 推送通知 | push notification webhook | 出站回调（Card `pushNotifications: true`）+ 回调签名校验；依赖长任务形态，排后 |
| 多模态 parts | file/data part | 与 04 分册多模态检索联动（图片问句） |

**v0.3 兼容**（挂起项）：真实 v0.3 Client 出现时再评估（响应形态整体差异大，单独立项）。

### 4.4 IM 多通道泛化

- `ImChannel` SPI 抽象（`DingTalkChatService` 的「消息→chatRag→回复」编排参数化）：listener 注册、消息标准化、回复渲染（markdown 溯源附录格式统一）、通道级限流/审计/指标（`rag.im.{channel}.*`）；
- 企微/飞书 adapter 各 ~1d（SDK 接入 + 消息格式差异），共享治理零新写。

## 5. 实施路径

| 批 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| 批1 | MCP Host 化（client starter + 外部工具并入 defaultTools + 授权矩阵/白名单治理） | 01 批1 授权矩阵 | E2E：agent 链经外部 MCP DB Server 真实查数回答；写类外部工具默认拒绝 |
| 批2 | A2A 出站联邦（A2aRemoteSubAgent + 机器凭证 + 出站内容守卫） | 外部测试 Agent（可用 a2a-samples 官方 helloworld） | E2E：编排链委派远端 Agent 回流结果；超时弃任务不击穿主链 |
| 批3 | A2A 流式（SendStreamingMessage + SSE 事件流 + Card 翻 true） | 批2 后按需 | 官方 a2a-sdk 流式 client 互操作验证 |
| 批4 | 任务生命周期方法 + 任务表 | 长任务场景出现 | GetTask/CancelTask 官方 client 验证 |
| 批5 | IM Channel SPI 泛化 + 企微/飞书 adapter | 企业 IM 选型 | 各 IM 端到端问答 + 治理核验 |

**风险**：a2a-java SDK Server 侧运行时绑定判负结论（Quarkus/CDI）对 Client 侧待核验——Client 侧依赖面小，不可行则自研 Client（JSON-RPC 三方法，成本 ~2d）；外部 MCP Server 质量参差（超时/错误注入）——统一经既有工具失败文本化回流承接。
