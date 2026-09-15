# MCP 集成指南

> 面向对象：需要将本企业知识库挂载为 MCP（Model Context Protocol）工具服务的外部 AI Agent / IDE / 自动化平台集成方。
> 版本：v1.0（2026-09-16，随 Phase4簇⑤ 4.10 实现 + Phase5簇⑥ 批2 后集成窗口整理）。
> 服务端实现依据：设计章 `../project-implement/11-*.md` §11.8；端点契约总表见 [API 文档](./API文档.md)。

## 1. 概述与定位

本系统内建 **MCP Server**（Spring AI `starter-webmvc`，Streamable HTTP 传输），把企业知识库暴露为三个只读工具，供任何标准 MCP Host（Claude Code / Cursor / 自研 Agent 框架等）挂载调用：

| 工具 | 能力 | 是否经 LLM |
|---|---|---|
| `search` | 混合检索（向量 + BM25 + RRF 融合 + 重排序），返回 Top-K 文档片段 | 否（纯检索管线） |
| `get_document` | 按文档 ID 读取整篇文档内容（元信息 + 按序片段） | 否 |
| `ask` | 面向知识库的 RAG 问答，返回带 `[ref-N]` 引用编号的回答 | 是（复用主答全链） |

**定位建议**：事实定位/证据查找用 `search`；需要跨文档综合成段回答用 `ask`；拿到引用后需要全文上下文用 `get_document`。让上层 Agent 按「先 search 定位、按需 ask 综合、按需 get_document 深读」组合使用效果最佳。

## 2. 服务信息

- **端点**：`POST {BASE_URL}/mcp`（Streamable HTTP，单端点；未启用 SSE 旧协议）
- **Server 名称/版本**：`kb-rag-agent` / 1.0.0（initialize 握手返回）
- **启用状态**：服务端默认装配，**无需开关**；工具治理（限流/审计/scope）有独立配置（见 §6）
- **协议版本**：MCP Streamable HTTP（2025-03-26 及后续修订），由 Spring AI starter 协商

## 3. 认证与身份

端点要求 **JWT Bearer 认证**（与 REST API 同一套 Casdoor 签发的 JWT）：

```
Authorization: Bearer <JWT>
```

身份守卫（fail-closed，三层任一不过即拒绝）：

1. **JWT 在场且有效**——缺失/过期 → HTTP 401；
2. **租户/用户 claim 完整**——JWT 须含 `owner`（租户）与 `sub`（用户）claim，缺失 → 工具错误 `IDENTITY_INCOMPLETE`；
3. **scope 治理（可选）**——服务端配置 `rag.mcp.scope.required` 非空时，JWT `scope` 声明须包含该值，否则 `MCP_SCOPE_DENIED`（供 Casdoor 应用级授权收敛；默认空 = 仅租户纪律）。

**数据边界**：所有工具的可见范围严格限定在 JWT `owner` 所指租户内（跨租户文档一律 `MCP_DOC_NOT_FOUND`，不泄露存在性）；软删片段不返回。

## 4. 工具契约

### 4.1 `search` —— 混合检索

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | 是 | 自然语言检索问题 |

**管线**：查询改写 → 向量 + BM25 双路并行召回 → RRF 融合 → 重排序，返回 Top-K。

**返回**：`SearchHitView` 数组（按相关度降序）：

| 字段 | 说明 |
|---|---|
| `chunkId` | 片段 ID（前缀即文档 ID，可作为 `get_document` 入参） |
| `fileName` / `headingPath` / `pageNum` | 来源文件名 / 标题路径 / 页码（Markdown 语料无页码概念时为 null/0） |
| `chunkType` | 片段类型（TEXT/TABLE 等） |
| `content` | 片段正文 |
| `rerankScore` / `finalRank` | 重排序得分 / 最终名次 |

### 4.2 `get_document` —— 文档全文

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `documentId` | string | 是 | 文档 ID（`kb_document` 主键；可取 `search` 结果 `chunkId` 前缀） |

**返回**：`DocumentView`（`documentId/name/type/status/parseRoute/pageCount/chunkCount` + `chunks[]`：`chunkIndex/headingPath/pageNum/content`，按序、软删过滤、超长截断上限由服务端配置）。

### 4.3 `ask` —— RAG 问答

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `question` | string | 是 | 面向知识库的自然语言问题 |

**返回**：string——答案正文，含 `[ref-N]` 引用编号锚定（编号与该轮检索证据一一对应）；空证据时返回拒答说明（不编造）。

**链路语义**：复用主答全链（意图路由 / 输入护栏 / 多模型路由 / 输出护栏 / 审计），每次调用为**独立会话**（`mcp-{随机ID}`）——无多轮记忆，追问请携带完整上下文重新提问。

## 5. 治理与观测（服务端视角，集成方需知）

| 面向 | 语义 |
|---|---|
| 限流 | `search`/`get_document` 走**独立租户桶**（缺省 120 次/60s，超限 `RATE_LIMITED` 经错误帧回传）；`ask` 复用对话链限流/配额（生成成本高，密度天然受限） |
| 审计 | `search`/`get_document` 落轻量审计行（`kb_audit_log.mode=mcp`，默认开）；`ask` 经全链审计（mode=rag） |
| 指标 | `rag.tool.call.*` / MCP 限流计数（Prometheus `rag_*` 族） |
| 超时 | `ask` 为同步生成（主模型思考形态 10-30s 量级），Host 侧工具调用超时建议 ≥120s；`search` 亚秒至数秒 |

## 6. 服务端配置（供运维按需调整）

```yaml
rag.mcp:
  scope:
    required: ""            # 非空 = JWT scope 须包含该值（应用级授权收敛）
  ratelimit:
    enabled: true
    tenant: { rate: 120, interval-seconds: 60 }   # 只读工具独立租户桶
  audit:
    db-enabled: true        # mode=mcp 轻量审计行
```

## 7. Host 集成示例

### Claude Code

```bash
claude mcp add --transport http kb-rag-agent \
  http://localhost:8090/mcp \
  --header "Authorization: Bearer <JWT>"
```

### 通用 mcpServers JSON（Cursor / 其他 Streamable HTTP Host）

```json
{
  "mcpServers": {
    "kb-rag-agent": {
      "type": "http",
      "url": "http://localhost:8090/mcp",
      "headers": { "Authorization": "Bearer <JWT>" }
    }
  }
}
```

（各 Host 配置字段名随版本演进，以其当期文档为准；核心要素 = Streamable HTTP 端点 + Bearer 头。）

### ⚠️ JWT 生命周期

Casdoor JWT 有有效期，**静态嵌入配置的 token 过期后调用将 401**——集成方需安排：

- 低频场景：过期后手工替换配置中的 token；
- 常驻场景：以脚本定期换取新 JWT 并热更新 Host 配置，或在网关侧注入凭据；
- 生产建议：为集成方在 Casdoor 建独立应用/客户端凭据形态，便于轮换与审计。

## 8. 故障排查

| 现象 | 定因 |
|---|---|
| HTTP 401 | JWT 缺失/过期——检查 `Authorization: Bearer` 头 |
| 工具错误 `IDENTITY_INCOMPLETE` | JWT 缺 `owner`/`sub` claim（非本系统 Casdoor 签发） |
| 工具错误 `MCP_SCOPE_DENIED` | JWT scope 不含服务端 `rag.mcp.scope.required` 配置值 |
| 工具错误 `MCP_DOC_NOT_FOUND` | 文档不存在**或**跨租户（不泄露存在性，两者同码） |
| 工具错误 `RATE_LIMITED` | 触发租户级限流（120 次/60s）——降低调用密度或联系运维调参 |
| `ask` 超时 | Host 工具超时配低了（建议 ≥120s）或服务端模型路由降级中 |
| 404 `/mcp` 无路由 | 部署版本过旧或经反代未放行该路径 |
