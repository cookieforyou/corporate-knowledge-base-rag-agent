# A2A 集成指南

> 面向对象：需要把本系统作为 A2A（Agent2Agent）网络中的问答 Agent 调用的外部 Agent / 编排框架 / 业务系统集成方。
> 版本：v1.0（2026-09-16，随 Phase5簇⑥ 批2 自研协议层交付整理）。
> 服务端实现依据：设计章 `../project-implement/11-*.md` §11.8/§11.5；实现背景（官方 Java SDK 判负与 v1.0 spec 权威核验）见 `../project-progress/sub-cluster-progress/Phase5簇⑥产品化收尾实施方案（批次推进版）.md` §4.2。

## 1. 概述与定位

本系统暴露一个 **A2A v1.0 协议端点**，对外提供「企业知识库检索问答」能力（skill：`kb_qa`）：

- **自研协议适配层**（零第三方 SDK），JSON-RPC 2.0 `SendMessage` **同步应答** + Agent Card 发现；
- 一次调用 = 一次完整的 RAG 问答（混合检索 + 重排序 + 带溯源生成），回答携带 `[ref-N]` 引用编号；
- **能力边界**（Card 如实声明，集成方据此设计）：非流式（`streaming=false`）、无推送通知（`pushNotifications=false`）、仅文本输入输出（`text/plain`）、不支持任务查询/取消等扩展方法（最小形态仅 `SendMessage`）。

## 2. 协议要点（v1.0 单版本）

| 要素 | 约定 |
|---|---|
| 协议版本 | **仅支持 v1.0**——请求必须携带 `A2A-Version: 1.0` 头；缺失或非 1.0 → 错误 `-32009`（spec §3.6.2：空头按 0.3 解释，本端点不兼容 0.3） |
| 方法名 | PascalCase **`SendMessage`**（v1.0 形态；非 0.3 时代的 `message/send`） |
| 传输 | HTTP POST JSON（JSON-RPC 2.0），同步阻塞至回答完成返回 |
| 响应 | `result.task`（oneOf 包装），终态直返 `TASK_STATE_COMPLETED` |

## 3. 服务端启用（运维前置）

A2A 端点默认关闭（`rag.a2a.enabled=false`，端点 404 缺位）。启用需两项环境变量（缺 `CARD_URL` 启动即失败，fail-closed）：

```
RAG_A2A_ENABLED=true
RAG_A2A_CARD_URL=https://<对外域名>/a2a    # Agent Card 声明的服务地址（生产 = 对外可达端点）
```

## 4. 发现：Agent Card

```
GET {BASE_URL}/.well-known/agent-card.json
Authorization: Bearer <JWT>
```

> ⚠️ **与公开发现惯例的差异**：本端点 Card **要求认证**（与消息端点同策略——Card 含内网端点信息，不做匿名公开发现）。集成流程中拉卡与发消息使用同一 JWT。

字段速览（完整形态以实际响应为准）：

| 字段 | 值/说明 |
|---|---|
| `name` | Enterprise KB RAG Agent |
| `supportedInterfaces[0]` | `{ url, protocolBinding: "JSONRPC", protocolVersion: "1.0" }`——**消息端点取此 `url`** |
| `capabilities` | `{ streaming: false, pushNotifications: false }` |
| `securitySchemes` | bearer（`httpAuthSecurityScheme`，JWT） |
| `defaultInputModes` / `defaultOutputModes` | `["text/plain"]` |
| `skills[0]` | `kb_qa`（企业知识库问答） |

## 5. 认证

与 REST API 同一套 Casdoor JWT：

```
Authorization: Bearer <JWT>
```

- 缺失/过期 → HTTP **401**（Spring Security 层拦截，不到达协议层）；
- JWT 须含 `owner`（租户）与 `sub`（用户）claim——缺失 → `-32603` 固定话术（不泄露细节）；
- 可选 scope 治理：服务端 `rag.a2a.scope.required` 非空时 JWT scope 须包含（默认空）；
- 数据边界：检索与回答严格限定在 JWT `owner` 租户内。

## 6. `SendMessage` 调用

### 请求（curl）

```bash
curl -s -X POST $HOST/a2a \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{
        "role":"ROLE_USER",
        "parts":[{"text":"<知识库文档内的问题>"}],
        "messageId":"m-1"}}}'
```

要点：
- `A2A-Version: 1.0` 头**必带**；
- 仅支持 **text part**（`{"text": "…"}`；空 part/其他形态 → `-32602`）；多个 text part 按序拼接；
- `contextId` 缺省由服务端生成并在响应回填——**多轮延续 = 后续请求携带同一 `contextId`**（服务端以其派生会话记忆域，同 contextId 追问可指代上文）。

### 响应

```json
{
  "jsonrpc": "2.0", "id": "1",
  "result": { "task": {
      "id": "<taskId>", "contextId": "<会话ID，多轮请携带>",
      "status": { "state": "TASK_STATE_COMPLETED", "timestamp": "2026-09-15T16:21:15.326Z" },
      "artifacts": [ { "artifactId": "…", "name": "answer", "parts": [ { "text": "<答案，含 [ref-N]>" } ] } ]
  } }
}
```

同步语义：请求阻塞至回答完成（主模型思考形态 **10-30s 量级**，客户端超时建议 ≥120s），单次响应即终态。

## 7. 错误码

| code | 触发 | 说明 |
|---|---|---|
| HTTP 401 | JWT 缺失/过期 | 安全层拦截 |
| -32009 | `A2A-Version` 头缺失或非 1.0 | 仅支持 v1.0 |
| -32601 | method 非 `SendMessage` | 最小形态，未实现 GetTask/Cancel 等 |
| -32602 | params/message/parts 缺失或非 text part | 仅支持文本 |
| -32603 | 服务端处理失败（含限流、身份不完整、注入拒绝） | 固定话术零细节泄露；限流话术可辨（「请求过于频繁」），其余统一「请求无法处理」 |
| HTTP 400 | 请求体非合法 JSON / Content-Type 错误 | Spring 框架层拒绝（非 JSON-RPC 错误帧）；`jsonrpc` 字段值不做校验（宽松接受） |

## 8. 客户端集成示例

### 官方 Python SDK（a2a-sdk ≥ 1.0，推荐）

冒烟脚本随仓库交付：`tools/other/a2a_client_check.py`（已在 a2a-sdk 1.1.2 实测通过）。核心形态：

```python
import httpx
from a2a.client import ClientConfig, ClientFactory
from a2a.types.a2a_pb2 import Message, Part, Role, SendMessageRequest

async with httpx.AsyncClient(
    headers={"Authorization": f"Bearer {TOKEN}"}, timeout=180,
) as hc:                                  # Bearer 经共享 httpx_client 注入：拉卡与发消息同源带认证
    factory = ClientFactory(ClientConfig(
        httpx_client=hc, streaming=False, accepted_output_modes=["text/plain"]))
    client = await factory.create_from_url(BASE_URL)   # 拉卡（带 Bearer）+ 建客户端（端点取 Card url）
    msg = Message(message_id="m-1", role=Role.ROLE_USER, parts=[Part(text="问题")])
    async for resp in client.send_message(SendMessageRequest(message=msg)):
        if resp.HasField("task"):
            ...  # resp.task.status.state / context_id / artifacts[].parts[].text
```

说明：a2a-sdk 1.x 自动携带 `A2A-Version` 协商头并使用 v1.0 方法名，与本端点开箱互通；会话内 token 过期需重建 client（重设 httpx_client headers）。

### 任意 HTTP 客户端

按 §6 curl 形态直发 JSON-RPC 即可（三要素：`Authorization` 头 + `A2A-Version: 1.0` 头 + `SendMessage` 方法体）。

## 9. 治理与观测（服务端视角，集成方需知）

| 面向 | 语义 |
|---|---|
| 限流 | 独立租户桶（缺省 20 次/60s，超限 `-32603` 限流话术） |
| 审计 | 入口轻量行（`kb_audit_log.mode=a2a`，默认关，`RAG_A2A_AUDIT_ENABLED=true` 开启）+ 对话链全链行（mode=rag），两行同 trace_id 关联 |
| 指标 | Prometheus `rag.a2a.*`（request/ratelimited/error/chat_duration） |
| 会话 | 记忆域 `a2a-{去横线 contextId}`，多轮 TTL 24h——过期后同 contextId 再问相当于新会话（无跨期持久化，设计边界） |

## 10. 集成注意事项

1. **同步阻塞语义**：A2A 消息调用独占一个 HTTP 请求直至回答完成——编排方并发调用请按「问答延迟 10-30s、超时 ≥120s」规划线程/连接池；
2. **空证据拒答**：库内无据可查时回答为拒答说明而非编造——编排方不应将拒答视为故障；
3. **引用编号消费**：回答中的 `[ref-N]` 与当轮检索证据对应，编排方需要溯源时可结合 MCP `search`/`get_document` 工具（见 [MCP 集成指南](./MCP集成指南.md)，A2A 与 MCP 可组合使用：A2A 做问答入口、MCP 做证据深读）；
4. **单轮幂等性**：同一 messageId 重发会再次执行完整问答（非幂等去重，最小形态未实现 messageId 去重）——重试语义由调用方自行控制。
