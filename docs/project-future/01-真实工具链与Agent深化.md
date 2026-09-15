# 01 · 真实工具链与 Agent 深化

> 状态：设计预案（未立项）· 基线：项目全阶段收官形态（2026-09-16）· 触发条件：真实 OA/ERP/DB 工具立项

## 1. 现状盘点

| 层 | 现状 | 成熟度 |
|---|---|---|
| 机制层 | HITL 三段式审批（挂起→approve→一次性消费）、Redis 账本 TTL + 租户/用户绑定、toolContext 通道、SSE TOOL_CALL 帧 | ✅ 完备（Phase 3/5 多轮 E2E 实证） |
| 编排层 | Orchestrator-Workers 三子代理（检索/数据/撰写）、委派预算闸、检索预算闸、TaskBoundaryAdvisor 任务边界 | ✅ 完备（演示 E2E 10/10） |
| 工具层 | `EnterpriseMockReadTools` / `EnterpriseMockWriteTools`——**契约对齐真实 OA/ERP**（读自动执行/写走 HITL），但无真实系统对接 | 🟡 Mock 演示层（D4 留存定案：唯一正确替换时机 = 真实工具立项） |
| 契约文档 | 真实工具挂接契约六条（§11.5.5）：Spec 注册/工具实现契约/跨层 HITL 升级路径/usage 聚合/并发串行定谳/Mock 留存 | ✅ 在档 |
| 观测 | `rag.tool.call.*` / `rag.orchestrator.*` 指标族 + 审计 tool_calls 快照 | ✅ 完备 |

## 2. 差距与痛点

1. **无真实系统对接**——数据查询子代理回答的是 Mock 数据；「任务完成率 >85%」验收因此以收窄口径（演示通过率）替代；
2. **凭证管理缺失**——真实 OA/ERP 调用需 per-user 或 service account 凭证：存储（明文 env 不可接受）、注入（toolContext 已有通道但无密钥保管）、轮换、审计；
3. **工具权限粒度**——当前全员可用全部工具；真实化后需「工具 × 角色 × 租户」授权矩阵（写工具天然 HITL，读工具也需可见性管控）；
4. **错误语义**——真实系统会超时/限流/业务报错；Mock 无错误注入路径，工具失败回流话术（TaskTool 失败文本化回流已有）未经真实错误形态检验；
5. **跨链割裂**——用户须显式指定 `mode: rag|tool|agent`；真实工具落地后混合流量出现，5.4-A 跨链路由（归档）复活条件满足。

## 3. 目标形态

```
用户 ──(mode: auto 可选)──▶ 链路路由器（5.4-A 复活）
   rag 链（现状不变）      tool/agent 链
                            ├─ EnterpriseTool SPI（真实工具实现层）
                            │    ├─ OA 适配器（请假/报销/审批流）
                            │    ├─ ERP 适配器（订单/库存/财务查询）
                            │    ├─ DB 查询工具（只读 SQL → 参数化查询模板）
                            │    └─ MCP 外部工具（见 02 分册——MCP Host 化供给）
                            ├─ 凭证保管（Vault/KMS 或 DB 加密 + toolContext 注入）
                            ├─ 工具授权矩阵（工具 × 角色 × 租户）
                            └─ HITL（现状机制全量复用，无改动）
```

## 4. 市面主流技术对照（2026）

| 方向 | 主流形态 | 对照结论 |
|---|---|---|
| 工具接入协议 | **MCP** 已成事实标准（官方/社区 Server 生态覆盖 DB/浏览器/文件系统/API）；LangChain/各家框架均支持；企业内部系统多以 OpenAPI 描述 | 双通道并存：**标准工具走 MCP Host 消费（02 分册），自有系统走 OpenAPI → 工具自动生成 + 手写适配器**；Spring AI 生态内 `@Tool` 契约稳定 |
| OpenAPI → 工具 | OpenAPI 规范自动生成工具定义（schema/描述即工具契约）——业界成熟做法；Spring AI 侧可经 RestClient 封装 | ERP/OA 已有 OpenAPI 的系统优先自动生成，减少手写面 |
| 凭证保管 | HashiCorp Vault / 云 KMS 为企业标准；轻量形态 = DB 加密列 + 信封加密 | 按部署形态分级：单机 DB 加密起步，规模化升 Vault |
| 工具沙箱 | 代码执行类工具入容器沙箱（Firecracker/gVisor 级隔离）；API 调用类不涉沙箱 | 本项目工具面为 API 调用型，暂不需要执行沙箱；DB 工具以只读账号 + 查询模板约束 |
| Agentic 权限 | 工具级 RBAC + 敏感操作 HITL 为行业共识（OWASP LLM Top 10 Agent 面指引） | HITL 已内建；补工具级 RBAC 即可对齐 |

## 5. 设计方案

### 5.1 EnterpriseTool SPI 与适配器层

- **契约**：实现 §11.5.5 契约六条——`SubAgentSpec` 注册真实工具集（不含 task 防递归）；工具方法 `@Tool` 注解 + 入参 schema；读工具自动执行、写工具走既有 HITL 三段式（**机制层零改动**）；
- **适配器形态**：OA/ERP 各自独立适配器类（对齐 Mock 命名契约，替换即删除 Mock——D4 留存分析的既定路径）；DB 工具 = 参数化查询模板（预定义 SQL 模板 + 参数槽，杜绝自由 SQL 注入面）；
- **幂等**：写工具调用携带幂等键（approvalId 复用），真实系统重试不产生重复单据。

### 5.2 凭证保管与注入

- **存储**：L1 = DB 加密列（AES-GCM，主密钥 env 注入）承载 service account 凭证；L2 = 规模化后升 Vault/KMS；
- **注入**：凭证**只在工具执行点解密经 toolContext 传入**，不进 Prompt、不进日志（既有 toolContext 物理隔离纪律延续）；per-user 代办场景经 OAuth2 授权码流换取用户令牌（OA 支持 SSO 时）；
- **审计**：凭证使用事件独立审计行（谁的工具调用用了哪类凭证——不含凭证本体）。

### 5.3 工具授权矩阵

- 模型：`工具 × 角色（普通/租户管理员/超管）× 租户开关`；落 DB 配置表 + 管理端维护；
- 执行点：ToolCallingAdvisor 前置守卫（对齐 McpIdentityGuard 形态），未授权工具对模型**不可见**（不注入 schema，从根上杜绝误调用）而非调用时报错。

### 5.4 跨链 mode=auto 路由（5.4-A 复活，归档 §11.4.1 路径）

- **位置**：Controller/Service 层选链（两链之上，异于链内 440）；
- **判据**：意图分类扩展三值（知识问→rag / 事务办理→tool / 复合任务→agent）+ `approvedToolCallId` 非空硬路由 tool 链 + fail-open 回落 rag；
- **契约**：`mode: auto` 新增枚举值（缺省仍 rag 显式），前端可选「智能」模式。

### 5.5 任务完成率门禁复活

- 原验收「Multi-Agent 任务完成率 >85%」随真实工具 E2E 复活：真实 OA/ERP 场景任务集（如「查我年假余额并提交请假」）≥10 例，成功率门禁入 kb-eval（对齐演示 E2E 形态，真实系统以录制桩/沙箱环境跑评估）。

## 6. 实施路径（建议批次）

| 批 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| 批1 | DB 查询工具（最简真实工具：只读账号 + 查询模板）+ 授权矩阵 + 凭证 L1 | 目标库只读账号 | E2E：agent 链委派 data-query 真实查库回答；未授权工具零可见 |
| 批2 | OA 适配器（SSO per-user 令牌 + 写工具 HITL 全链）+ 幂等键 | OA 测试环境 + SSO 对接 | E2E：请假申请 HITL 三段式真实提交；重试无重复单据 |
| 批3 | ERP 适配器（OpenAPI 自动生成）+ Mock 下线 | ERP OpenAPI | 契约对齐核验；任务完成率门禁复活首轮读数 |
| 批4 | 跨链 mode=auto 路由 + 前端「智能」模式 | 批1-3 流量形态稳定 | 混合任务集分流准确率 E2E；fail-open 回落验证 |

**风险与预案**：真实系统测试环境稳定性（评估跑批用录制桩兜底——StubChatServer 先例）；OA/ERP API 变更（适配器层薄封装 + 契约测试钉死）。
