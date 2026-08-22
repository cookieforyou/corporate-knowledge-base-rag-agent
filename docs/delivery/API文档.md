# API 文档 · 企业知识库 RAG Agent 工作台

> 版本：1.1（2026-08-22，随 Phase 4 簇⑦交付；契约逐字核对源码：kb-api / kb-admin / kb-ai-agent / kb-commons）
> 基地址：`http://<host>:8090` · 配套：[运维手册](./运维手册.md) · [用户使用手册](./用户使用手册.md)

---

## 1. 通用约定

### 1.1 认证

OAuth2 Resource Server JWT Bearer：`Authorization: Bearer <token>`（无状态，CSRF 关闭）。令牌经 Casdoor OAuth2/PKCE 获取，claims 映射：`sub → userId`、`name → username`、`owner → tenantId`、`displayName → 展示名`。

**鉴权矩阵**：`/api/**` 与 `/mcp` = authenticated；`/actuator/health|info|metrics|metrics/**|prometheus` = permitAll；`/ws/**` = permitAll（鉴权在握手层）；其余 = denyAll。

**租户守卫（fail-closed）**：JWT 通过 ≠ 身份完整——`owner` claim 缺失在入口即拒 `IDENTITY_INCOMPLETE`(400)。kb-api 经 JwtUtils，kb-admin/MCP 经 `@AuthenticationPrincipal Jwt` 直读。所有数据面按调用者租户强制过滤；跨租户访问一律 `*_NOT_FOUND`（不泄露存在性）。

### 1.2 响应包裹（ApiResponse）

```json
{ "code": 200, "message": "success", "data": { } }
```

`record ApiResponse(int code, String message, T data)`，`@JsonInclude(NON_NULL)`（null 字段不输出）。错误形态 `code` 为 HTTP 状态码（如 400/409/413/429/503），`data` 缺省。**SSE 流式端点例外**（§4，裸协议帧，业务异常转 ERROR 帧不经全局异常处理）。

### 1.3 错误码 → HTTP 映射（GlobalExceptionHandler）

| 规则 | HTTP |
|---|---|
| `RATE_LIMITED` / `TOKEN_BUDGET_EXCEEDED` | **429** |
| `DOC_NOT_READY` / `CHUNK_NOT_DELETED` | **409** |
| `APPROVAL_STORE_UNAVAILABLE` / `REBUILD_STORE_UNAVAILABLE` | **503** |
| `FILE_TOO_LARGE`（及 multipart 超限异常） | **413** |
| 其余业务错误码 | **400** |
| 参数校验失败 | 400；其他未捕获异常 | 500 |

**全量错误码清单**：

| 域 | 错误码（均 400，除已标注） |
|---|---|
| 身份/入口 | `IDENTITY_INCOMPLETE`（全端点 owner 缺失）、`INVALID_MODE`、`PROMPT_INJECTION`（流式转 ERROR 帧） |
| 配额 | `RATE_LIMITED`（429）、`TOKEN_BUDGET_EXCEEDED`（429） |
| 文档 | `FILE_EMPTY`、`FILE_TYPE_UNSUPPORTED`、`UPLOAD_FAILED`、`DOC_NOT_FOUND`、`DOC_FORBIDDEN`、`DOC_NOT_READY`（409）、`ETL_FAILED` |
| Chunk | `CHUNK_NOT_FOUND`、`CHUNK_NOT_DELETED`（409） |
| 会话/反馈 | `SESSION_NOT_FOUND`、`MESSAGE_NOT_FOUND`、`INVALID_FEEDBACK`、`FEEDBACK_NOT_FOUND` |
| 审计/回灌 | `AUDIT_LOG_NOT_FOUND`、`INVALID_ROOT_CAUSE`、`INVALID_TIME_FORMAT`、`GOLDEN_ENTRY_INVALID`、`GOLDEN_FILE_CORRUPT`、`GOLDEN_DIR_UNAVAILABLE` |
| 重建 | `REBUILD_TASK_NOT_FOUND`、`REBUILD_STORE_UNAVAILABLE`（503） |
| 词表 | `GUARDRAIL_RULE_NOT_FOUND`、`GUARDRAIL_RULE_INVALID`、`GUARDRAIL_RULE_DUPLICATE` |
| MCP | `MCP_QUERY_EMPTY`、`MCP_DOC_NOT_FOUND`、`MCP_SCOPE_DENIED`、`RATE_LIMITED`（429） |
| 工具 | `APPROVAL_STORE_UNAVAILABLE`（503） |

HTTP 401/403 由安全过滤器层产生（无业务错误码）。

### 1.4 请求体限制

- 上传：multipart 单文件 **50MB** / 单请求 **60MB**，超限 413
- `/api/v1/chat` 前缀：JSON 请求体 `Content-Length` > `app.chat.max-body-size`（缺省 **1MB**）先行拦截 413（chunked 传输不拦，登记观察）

---

## 2. 业务端点（`/api/v1`，kb-api）

### 2.1 对话（AgentController）

#### `POST /api/v1/chat` —— 同步对话

请求体：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | 是 | 用户问题 |
| `sessionId` | string | 否 | 多轮会话标识；缺省后端生成并随响应回传 |
| `mode` | `rag`\|`tool` | 否 | 双链路显式分流，缺省 `rag`；非法值 `INVALID_MODE` |
| `approvedToolCallId` | string | 否 | HITL 批准后的一次性消费凭证（tool 链二次对话） |

响应 `data`：`{answer, sessionId, messageId, traceId, toolCalls[]}`。

#### `POST /api/v1/chat/stream` —— 流式对话（主入口）

`Content-Type: text/event-stream`，请求体同上，协议帧见 §4。`messageId`/`traceId` 为请求级 UUID（Controller 请求线程生成）。

### 2.2 文档（DocumentController，`/api/v1/documents`）

| 方法·路径 | 参数 | 响应 data |
|---|---|---|
| `POST /upload` | multipart `file`（必填）+ `parseRoute`（可选 `NATIVE\|DEEP\|OCR`） | `{docId}` |
| `GET /` | — | 文档列表（创建时间倒序） |
| `GET /{id}` | path | KbDocument |
| `GET /{id}/chunks` | path | chunk 列表（序号升序） |
| `DELETE /{id}` | path | `{deleted: id}`（幂等——重复删静默成功；处理中 409） |
| `POST /{id}/reparse` | path + `parseRoute` 可选 | `{docId, status:"REINDEXING"}` |
| `POST /{id}/replace` | path + multipart `file` + `parseRoute` 可选 | `{docId, status:"REINDEXING"}` |

**KbDocument**：`id, tenantId, name, originalName, type(PDF/DOCX/PPTX/XLSX/MD/TXT/HTML), size, ossPath, status, parseRoute, pageCount, tableCount, imageCount, chunkCount, errorMessage, version, createdBy, createdAt, updatedAt`。
**状态机**：`UPLOADING → PARSING → SUCCESS/FAILED`；重入库窗口 `REINDEXING`（处理期禁删/禁重入）。
**KbChunk**：`id, docId, sectionId, chunkIndex, content, originalContent, pageNum, tokenCount, metadata, chunkType, vectorId, isDeleted, createdAt, updatedAt, headingPath`。

### 2.3 检索调试（RetrievalDebugController）

#### `POST /api/v1/retrieval/search`

直调检索链（不经 LLM、不写审计）。请求体 `{query}`。

响应 `data`：`{query, rewrittenQuery, latencyMs{rewrite,retrieval,rerank,total}, candidates[], degradation{vector:"OK|DEGRADED", bm25:"OK|DEGRADED"}}`。
`Candidate`：`{chunkId, fileName, pageNum, chunkType, content, vectorScore, vectorRank, bm25Score, bm25Rank, fusionScore, rerankScore, rerankRank, finalRank}`（未出现的路其字段为 null）。改写结果恒展示（不受 `rag.retrieval.rewrite.enabled` 影响）。

### 2.4 会话（SessionController，`/api/v1/sessions`，tenant+user 双过滤）

| 方法·路径 | 参数 | 响应 data |
|---|---|---|
| `GET /` | `page`（0 基，缺省 0）、`size`（缺省 50，上限 100） | `[{id, title, messageCount, updatedAt}]` |
| `GET /{id}/messages` | path | `[{id, role, content, createdAt, sources(TRACE 帧同形，可 null), traceId, feedback}]` |
| `DELETE /{id}` | path | `{deleted: true}` |

跨租户/用户访问 → `SESSION_NOT_FOUND`。

### 2.5 反馈（FeedbackController，`/api/v1/feedback`）

| 方法 | 请求 | 响应 data |
|---|---|---|
| `POST /` | `{messageId(必填), rating(必填, POSITIVE\|NEGATIVE 大小写不敏感), traceId, expectedAnswer, tags}` | `{feedbackId, rating}`（upsert 可改评） |
| `GET /` | query `rating, resolved(Boolean), limit(缺省50上限100)` 全可选 | `[{feedbackId, messageId, sessionId, rating, expectedAnswer, tags, resolved, createdAt, auditLogId, query, answer}]` |

归属经 message→session 校验 fail-closed；错误 `INVALID_FEEDBACK` / `MESSAGE_NOT_FOUND`。

### 2.6 工具审批（ToolApprovalController）

#### `POST /api/v1/tools/approvals/{approvalId}/approve`

校验存在 + tenant/user 绑定 + `PENDING → APPROVED`。响应 `{approved: boolean}`；跨租户/不存在 → `approved: false`（不暴露细节）。错误 `IDENTITY_INCOMPLETE` / `APPROVAL_STORE_UNAVAILABLE`(503)。

批准后经二次对话携带 `approvedToolCallId` 一次性消费；审批账本 Redis（TTL 10 分钟 + 租户/用户绑定），Redis 故障写工具拒绝执行（503，**fail-closed 设计**）。

### 2.7 统计（StatsController，`/api/v1/stats`）

| 方法·路径 | 响应 data |
|---|---|
| `GET /overview` | `{documentTotal, documentsByStatus, chunkTotal(存活精确), documentsByParseRoute, dailyIngestion[14天]}` |
| `GET /documents/processing` | `{counts{UPLOADING,PARSING,REINDEXING}, documents[{id,name,status,parseRoute,updatedAt}]}` |

---

## 3. 运维后台端点（`/api/v1/admin`，kb-admin）

> JWT `owner` claim 租户守卫直消费（不复用 JwtUtils 防成环）。

### 3.1 Chunk 运维（ChunkAdminController，`/api/v1/admin/chunks`）

| 方法·路径 | 请求 | 说明 |
|---|---|---|
| `PUT /{chunkId}` | `{content}`（@NotBlank，≤30000 字符） | 编辑（同源消毒 → PG → 异步两步重嵌入 → ES 覆写） |
| `DELETE /{chunkId}` | — | 软删（幂等；检索即不可见） |
| `POST /{chunkId}/restore` | — | 恢复（重嵌入复位；未软删 409 `CHUNK_NOT_DELETED`） |

响应 `ChunkView`：`{id, docId, chunkIndex, content, chunkType, pageNum, isDeleted, headingPath, updatedAt}`。错误 `CHUNK_NOT_FOUND`（跨租户同返）/ `DOC_NOT_READY`(409)。

### 3.2 索引重建（RebuildController，`/api/v1/admin/rebuild`）

| 方法·路径 | 请求 | 说明 |
|---|---|---|
| `POST /` | `{docIds[]}`（可空 = 租户全量） | 发起重建（异步，任务表 Redis 租户域，重启保留） |
| `GET /tasks` | — | 任务列表（近 20 条） |
| `GET /tasks/{taskId}` | path | 任务详情；跨租户/不存在 `REBUILD_TASK_NOT_FOUND` |

`RebuildTaskView`：`{taskId, status, total, succeeded, failed, skipped, startedAt, finishedAt, failures[{docId, reason}]}`。存储不可用 `REBUILD_STORE_UNAVAILABLE`(503)。

### 3.3 Bad Case 闭环（BadCaseAdminController，`/api/v1/admin`）

| 方法·路径 | 请求 | 说明 |
|---|---|---|
| `GET /audit-logs` | query 全可选：`from, to, userId, sessionId, feedback, status, rootCause, annotated, page, size`（缺省 20 上限 100） | 审计日志分页查询；响应 `{items[], total, page, size}` |
| `PUT /audit-logs/{auditLogId}/root-cause` | `{rootCause}`（@NotBlank） | 根因四分类：`RETRIEVAL_MISS \| REWRITE_DRIFT \| HALLUCINATION \| PARSING_GAP` |
| `POST /badcase/reingest` | `{auditLogId(必填), category, expectedChunkIds[], expectedDocs[], expectedAnswer, expectedKeywords}` | Golden 回灌 Git Ops 通道 |
| `PUT /feedback/{feedbackId}/resolved` | `{resolved}`（必填 Boolean） | 反馈处理态闭环 |

`AuditLogView`：`{id, traceId, sessionId, userId, mode, queryText, rewrittenQuery, retrievalType, retrievedChunks, rerankedChunks, finalAnswer, toolCalls, modelName, latencyMs, tokenUsage, status(SUCCESS|REJECTED|ERROR), errorCode, feedback, rootCause, createdAt, feedbackExpectedAnswer}`（JSON 快照列原样透传字符串）。
`ReingestResult`：`{goldenId(bc-{auditLogId} upsert 幂等), file, question, category, resolvedFeedbackId}`；`category`：`FACTOID|REASONING|TABLE|MULTI_DOC|NEGATIVE`。
错误：`AUDIT_LOG_NOT_FOUND`（跨租户/不存在同返，不泄露存在性）/ `FEEDBACK_NOT_FOUND` / `INVALID_ROOT_CAUSE` / `INVALID_TIME_FORMAT` / `GOLDEN_*`。

### 3.4 护栏词表（GuardrailAdminController，`/api/v1/admin/guardrail`）

> 词表 DB 单轨运营面；**词条内容元数据形态——读路径不回显明文，写路径只收 `valueB64`**（Base64 编码，解码 ≤500 字符）。

| 方法·路径 | 请求 | 说明 |
|---|---|---|
| `GET /rules` | query 全可选：`side, family, lang, action, enabled, type, page, size`（缺省 20 上限 100） | 活视图分页；`GuardrailRuleView{id, side(injection\|output), family, lang, type(KEYWORD\|REGEX), action(BLOCK\|FLAG), enabled, sha256(指纹前12), charLen}` |
| `GET /rules/{id}` | path | 编辑预填：`GuardrailRuleEditView`（含 `valueB64, sha256, charLen, origin(MIGRATION\|API), createdBy, updatedBy, 时间戳`） |
| `POST /rules` | `{side, family, valueB64(必填), lang, type, action, enabled}`（缺省 type=KEYWORD, action=**FLAG**, enabled=true） | 新增（新建默认 FLAG 观察） |
| `PUT /rules/{id}` | 全可选（null = 保持原值） | 修改 |
| `DELETE /rules/{id}` | path | 物理删除 |
| `POST /reload` | — | 手动重载：`{source, reloaded, injectionCount, outputCount}`（本地同步 + pub/sub 广播） |
| `POST /drill` | `{text}`（@NotBlank） | 命中演练：`{injectionMatches[], outputMatches[]}`（同运行时口径，不计指标不落审计） |

写路径闭环：校验（Base64 → 解码非空 ≤500 字符 → family 枚举 → REGEX 预编译）→ 保存 → 自动重载（fail-keep 不阻断）→ 广播 → 编码 YAML 存档导出。错误：`GUARDRAIL_RULE_NOT_FOUND` / `GUARDRAIL_RULE_INVALID` / `GUARDRAIL_RULE_DUPLICATE`。

---

## 4. SSE 流式协议（`POST /api/v1/chat/stream`）

**无名帧**（`data:` 负载 JSON）：

| 帧 | 负载 | 说明 |
|---|---|---|
| TOKEN | `{"token":"..."}` | 增量文本，每 token 一帧 |
| ERROR | `{"error":"..."}` | 流内业务错误（错误路径**不发 DONE**） |
| DONE | `{"messageId":"<UUID>","traceId":"<UUID>"}` | 终止帧；两 ID 供反馈/审计关联 |

**命名帧**：

| 帧 | 负载 | 说明 |
|---|---|---|
| `TRACE` | `{"sources":[{"source":"vector\|bm25\|final","chunks":[{chunkId,docId,fileName,pageNum,scores{...},snippet(前120字)}],"latencyMs"}]}` | 仅 rag 链非免检索路径；**`final` 数组下标 ↔ `[ref-N]`** |
| `TOOL_CALL` | `{"toolCalls":[{toolName,status(PENDING_APPROVAL\|EXECUTED),approvalId,summary}]}` | 仅 tool 链非空；写工具挂起携带 `approvalId` |

**帧序**：`TOKEN* → (TRACE | TOOL_CALL) → DONE`。

---

## 5. WebSocket（ETL 进度）

端点：`ws://<host>:8090/ws/etl/progress`

- **握手鉴权**：`?token=<JWT>` 查询参数（浏览器 WS 无法携带头部），`JwtHandshakeInterceptor` 校验（失败 403）；Origin 白名单 `app.ws.allowed-origins`。
- **订阅**：`?docId=<id>` 自动订阅；客户端可发 `{"action":"subscribe"|"unsubscribe","docId":"..."}`。
- **推送帧**：`{docId, stage, documentCount, chunkCount, processedChunks, percentage}`（经 Redis Pub/Sub `etl:progress` 频道）。
- **阶段枚举**：`READING | TRANSFORMING | PERSISTING | EMBEDDING | INDEXING | CLEANUP | COMPLETED | FAILED`。

---

## 6. MCP Server（Streamable HTTP `/mcp`）

知识库以 MCP Server 暴露给标准 MCP Client（Claude Desktop / Cursor 等）。协议显式钉 `STREAMABLE`（sync），JWT authenticated。

**治理三层（McpIdentityGuard，fail-closed）**：
1. 无 JWT / `owner` 空白 → `IDENTITY_INCOMPLETE`；
2. `rag.mcp.scope.required` 非空时客户端 scope 须包含，否则 `MCP_SCOPE_DENIED`；
3. 独立限流桶 `rag:ratelimit:mcp:{tenant}`（缺省 120 次/60s，Redis 故障 fail-open），超限 `RATE_LIMITED`(429)。

轻量审计：日志恒开（摘要 40 字符）；DB 行 `rag.mcp.audit.enabled` 缺省关。独立会话前缀 `mcp-`（36 字符无横线）。**返回为裸 JSON（非 ApiResponse 包裹），错误经 MCP isError 帧**。容器无 ToolCallback Bean——HITL 工具不漏进 MCP 面。

| 工具 | 入参 | 返回 |
|---|---|---|
| `search` | `query`（必填） | `SearchHitView[]`：`{chunkId, fileName, headingPath, pageNum, chunkType, content, rerankScore, finalRank}`（直调检索链不经 LLM） |
| `get_document` | `documentId`（必填） | `DocumentView`：`{documentId, name, type, status, parseRoute, pageCount, chunkCount, chunks[{chunkIndex, headingPath, pageNum, content}]}`（存活 chunk，上限 `rag.mcp.get-document.max-chunks` 缺省 50；跨租户 `MCP_DOC_NOT_FOUND`） |
| `ask` | `question`（必填） | 纯文本答案（带 `[ref-N]`，经 ragAgentChatClient 全链——护栏/配额/审计自动复用；注入拒绝 `PROMPT_INJECTION`） |

---

## 7. actuator 暴露面（permitAll）

| 端点 | 用途 |
|---|---|
| `/actuator/health` | 存活/就绪（compose healthcheck 消费） |
| `/actuator/info` | 版本信息 |
| `/actuator/prometheus` | Prometheus 抓取（`rag.*` 业务指标 + JVM/HTTP） |
| `/actuator/metrics/**` | 指标明细 |

暴露面即以上四项（include 白名单钉死）；网络层源址收敛经 ECS 安全组（运维手册 §9 / 用户侧清单 SG1）。

---

## 8. 集成备忘

- `messageId`/`traceId` 为请求级 UUID（Controller 请求线程生成，随 DONE 帧回传）；`goldenId = bc-{auditLogId}`；`sessionId` 前端生成后复用即多轮。
- `ParseRoute` 枚举：`NATIVE | DEEP | OCR`；`RootCause` 四分类；`DocumentStatus` 五态。
- 上传类型白名单（ContentType 校验为唯一拦截面）：`application/pdf`、`application/vnd.openxmlformats-officedocument.{wordprocessingml.document, presentationml.presentation, spreadsheetml.sheet}`、`text/markdown`、`text/plain`、`text/html`。
