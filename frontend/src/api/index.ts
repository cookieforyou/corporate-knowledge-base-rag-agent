import axios from 'axios'
import router from '@/router'
import type { Source } from '@/stores/chat'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 300_000
})

// 请求拦截：注入 JWT
api.interceptors.request.use(config => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截：401 跳登录
api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('access_token')
      router.push('/login')
    }
    return Promise.reject(err)
  }
)

// ── 对话 ──

/** 同步问答 */
export const chat = (query: string) =>
  api.post('/chat', { query }).then(r => r.data.data.answer)

/** SSE 流式问答 */
export const chatStreamUrl = () => `${api.defaults.baseURL}/chat/stream`

// ── 历史会话（3.15 补齐：列表 / 消息含溯源恢复 / 删除）──

export interface SessionSummary {
  id: string
  title: string
  messageCount: number
  updatedAt: string
}

export interface HistoryMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  createdAt: string
  /** citations 解析结果，与 SSE TRACE 同形；存量数据/tool 轮/闲聊轮为 null */
  sources?: Source[] | null
  traceId?: string | null
  /** 当前用户既有反馈评价（upsert 语义至多一条），无则 null */
  feedback?: 'POSITIVE' | 'NEGATIVE' | null
}

export const listSessions = (page = 0, size = 50) =>
  api.get('/sessions', { params: { page, size } })
    .then(r => r.data.data as SessionSummary[])

export const getSessionMessages = (sessionId: string) =>
  api.get(`/sessions/${sessionId}/messages`)
    .then(r => r.data.data as HistoryMessage[])

export const deleteSession = (sessionId: string) =>
  api.delete(`/sessions/${sessionId}`)
    .then(r => r.data.data as { deleted: boolean })

// ── 工具调用与 HITL 审批（3.3/3.4/3.15）──

/** 工具调用投影：与后端 RetrievalContext.ToolCall / SSE TOOL_CALL 事件同形 */
export interface ToolCallInfo {
  toolName: string
  status: 'PENDING_APPROVAL' | 'EXECUTED' | 'REJECTED'
  approvalId?: string | null
  summary?: string | null
  /** 本地 UI 状态（非后端字段）：审批单失效（approve 返回 false） */
  expired?: boolean
}

/** HITL 审批确认（11.2.1 三段式第二段）：approved=false 表示凭证失效/越权 */
export const approveToolCall = (approvalId: string) =>
  api.post(`/tools/approvals/${approvalId}/approve`)
    .then(r => r.data.data.approved as boolean)

// ── 用户反馈（3.17：点赞/点踩 → kb_feedback，Bad Case 可查询）──

export interface FeedbackPayload {
  /** 被评价的助手消息 ID（SSE DONE 帧送达） */
  messageId: string
  /** 本轮 trace ID（关联审计行回填，可选） */
  traceId?: string | null
  rating: 'POSITIVE' | 'NEGATIVE'
  expectedAnswer?: string | null
  tags?: string[]
}

export const submitFeedback = (payload: FeedbackPayload) =>
  api.post('/feedback', payload)
    .then(r => r.data.data as { feedbackId: string; rating: string })

// ── 文档管理（2.15）──

export interface KbDoc {
  id: string
  name: string
  type: string
  size: number
  status: string
  parseRoute?: string
  chunkCount?: number
  pageCount?: number
  errorMessage?: string
  createdBy?: string
  createdAt: string
  /** 版本号（簇⑥ C1）：首次入库 1，每次重入库成功 +1 */
  version?: number
}

export interface KbChunk {
  id: string
  docId: string
  chunkIndex: number
  chunkType: string
  tokenCount?: number
  pageNum?: number
  content: string
  createdAt: string
  /** 软删标记（簇③ 4.4 运维面：列表含软删行，恢复操作可见性前提） */
  isDeleted?: boolean
  updatedAt?: string
  headingPath?: string
}

export const uploadDocument = (file: File, parseRoute?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (parseRoute) form.append('parseRoute', parseRoute)
  return api.post('/documents/upload', form).then(r => r.data.data.docId as string)
}

export const listDocuments = () =>
  api.get('/documents').then(r => r.data.data as KbDoc[])

export const getChunks = (docId: string) =>
  api.get(`/documents/${docId}/chunks`).then(r => r.data.data as KbChunk[])

export const deleteDocument = (docId: string) =>
  api.delete(`/documents/${docId}`).then(r => r.data.data)

/** 增量重入库——重解析：以 MinIO 原件重走 ETL（簇⑥ C1） */
export const reparseDocument = (docId: string, parseRoute?: string) => {
  const params = parseRoute ? `?parseRoute=${parseRoute}` : ''
  return api.post(`/documents/${docId}/reparse${params}`).then(r => r.data.data)
}

/** 增量重入库——替换：新文件覆盖原件后重走 ETL（簇⑥ C1） */
export const replaceDocument = (docId: string, file: File, parseRoute?: string) => {
  const form = new FormData()
  form.append('file', file)
  if (parseRoute) form.append('parseRoute', parseRoute)
  return api.post(`/documents/${docId}/replace`, form).then(r => r.data.data)
}

// ── 检索调试（2.14）──

export interface RetrievalCandidate {
  chunkId: string
  fileName?: string
  pageNum?: number
  chunkType?: string
  content?: string
  vectorScore?: number
  vectorRank?: number
  bm25Score?: number
  bm25Rank?: number
  fusionScore?: number
  rerankScore?: number
  rerankRank?: number
  finalRank?: number
}

export interface RetrievalDebugResult {
  query: string
  rewrittenQuery: string
  latencyMs: { rewrite: number; retrieval: number; rerank: number; total: number }
  candidates: RetrievalCandidate[]
  degradation: Record<string, string>
}

export const retrievalSearch = (query: string) =>
  api.post('/retrieval/search', { query }).then(r => r.data.data as RetrievalDebugResult)

// ── 运维中心（Phase 4 簇②④：统计仪表盘 + 审计日志 + Bad Case 闭环）──

export interface StatsOverview {
  documentTotal: number
  documentsByStatus: Record<string, number>
  chunkTotal: number
  documentsByParseRoute: Record<string, number>
  dailyIngestion: { date: string; documents: number; chunks: number }[]
}

export interface ProcessingDocument {
  id: string
  name: string
  status: string
  parseRoute?: string
  updatedAt: string
}

export interface ProcessingView {
  counts: Record<string, number>
  documents: ProcessingDocument[]
}

export const getStatsOverview = () =>
  api.get('/stats/overview').then(r => r.data.data as StatsOverview)

export const getProcessingStats = () =>
  api.get('/stats/documents/processing').then(r => r.data.data as ProcessingView)

/** 审计日志条目（簇④ 4.7）；JSON 快照列为原始字符串，前端按需解析 */
export interface AuditLogItem {
  id: number
  traceId?: string
  sessionId?: string
  userId?: string
  mode?: string
  queryText: string
  rewrittenQuery?: string
  retrievalType?: string
  retrievedChunks?: string
  rerankedChunks?: string
  finalAnswer?: string
  toolCalls?: string
  modelName?: string
  latencyMs?: number
  tokenUsage?: string
  status?: string
  errorCode?: string
  feedback?: string
  rootCause?: string
  createdAt: string
  feedbackExpectedAnswer?: string
}

export interface AuditLogPage {
  items: AuditLogItem[]
  total: number
  page: number
  size: number
}

export interface AuditQuery {
  from?: string
  to?: string
  userId?: string
  sessionId?: string
  feedback?: string
  status?: string
  rootCause?: string
  annotated?: boolean
  page?: number
  size?: number
}

export const searchAuditLogs = (params: AuditQuery) =>
  api.get('/admin/audit-logs', { params }).then(r => r.data.data as AuditLogPage)

export type RootCause = 'RETRIEVAL_MISS' | 'REWRITE_DRIFT' | 'HALLUCINATION' | 'PARSING_GAP'

/** Bad Case 根因标注（簇④ 4.7 四分类） */
export const annotateRootCause = (auditLogId: number, rootCause: RootCause) =>
  api.put(`/admin/audit-logs/${auditLogId}/root-cause`, { rootCause })
    .then(r => r.data.data as { auditLogId: number; rootCause: string })

export interface ReingestPayload {
  auditLogId: number
  category?: string
  expectedChunkIds?: string[]
  expectedDocs?: string[]
  expectedAnswer?: string
  expectedKeywords?: string
}

/** Golden Set 回灌（簇④ 4.7）：写入 badcase-qa.json，id=bc-{auditLogId} upsert */
export const reingestGolden = (payload: ReingestPayload) =>
  api.post('/admin/badcase/reingest', payload).then(r => r.data.data as {
    goldenId: string; file: string; question: string; category: string
    resolvedFeedbackId: string | null
  })

// ── Chunk 运维与索引重建（Phase 4 簇③ 4.4/4.5，运维中心前端面）──

/** Chunk 运维视图（ChunkView 投影同形） */
export interface ChunkOpsView {
  id: string
  docId: string
  chunkIndex: number
  content: string
  chunkType?: string
  pageNum?: number
  isDeleted: boolean
  headingPath?: string
  updatedAt?: string
}

/** Chunk 编辑：同源消毒 → PG 同步 → 异步重嵌入（chunk ID 不变） */
export const editChunk = (chunkId: string, content: string) =>
  api.put(`/admin/chunks/${chunkId}`, { content }).then(r => r.data.data as ChunkOpsView)

/** Chunk 软删（C1 管道），幂等 */
export const softDeleteChunk = (chunkId: string) =>
  api.delete(`/admin/chunks/${chunkId}`).then(r => r.data.data as ChunkOpsView)

/** 软删 Chunk 恢复：PG 复活 + 异步重嵌入 */
export const restoreChunk = (chunkId: string) =>
  api.post(`/admin/chunks/${chunkId}/restore`).then(r => r.data.data as ChunkOpsView)

export interface RebuildTask {
  taskId: string
  status: string
  total: number
  succeeded: number
  failed: number
  skipped: number
  startedAt?: string
  finishedAt?: string
  failures?: { docId: string; reason: string }[]
}

/** 发起索引重建：docIds 缺省 = 租户全量 */
export const startRebuild = (docIds?: string[]) =>
  api.post('/admin/rebuild', docIds?.length ? { docIds } : {})
    .then(r => r.data.data as RebuildTask)

export const listRebuildTasks = () =>
  api.get('/admin/rebuild/tasks').then(r => r.data.data as RebuildTask[])

export const getRebuildTask = (taskId: string) =>
  api.get(`/admin/rebuild/tasks/${taskId}`).then(r => r.data.data as RebuildTask)

// ── 护栏词表运维（安全簇⑥ F2 前端面：只读视图 + 命中演练）──

/** 词项运维视图（GuardrailRuleView 同形）——元数据形态，value 明文不回显（第七节纪律） */
export interface GuardrailRuleView {
  id: string
  side: 'injection' | 'output' | string
  family: string
  lang?: string
  type: 'KEYWORD' | 'REGEX' | string
  action: 'BLOCK' | 'FLAG' | string
  enabled: boolean
  /** value SHA-256 指纹前 12 位（跨通道比对锚点，不反推原文） */
  sha256: string
  charLen: number
}

export interface GuardrailRuleQuery {
  side?: string
  family?: string
  lang?: string
  action?: string
  enabled?: boolean
  type?: string
}

/** 词表列表查询（读注册表活快照，F1 热重载后即时一致）；六条件全可选 */
export const listGuardrailRules = (params: GuardrailRuleQuery = {}) =>
  api.get('/admin/guardrail/rules', { params })
    .then(r => r.data.data as GuardrailRuleView[])

/** 命中演练结果：输入文本归一化后注入/输出双侧命中清单（不计指标不落审计） */
export interface DrillResult {
  injectionMatches: GuardrailRuleView[]
  outputMatches: GuardrailRuleView[]
}

export const drillGuardrail = (text: string) =>
  api.post('/admin/guardrail/drill', { text }).then(r => r.data.data as DrillResult)

// ── ETL 进度 WebSocket（2.13）──

/**
 * ETL 进度 WebSocket URL。经 Vite /ws 代理转发至后端（vite.config ws:true）；
 * 浏览器 WS API 不支持自定义头，JWT 经 ?token= 传递，握手层校验。
 */
export const etlProgressWsUrl = (docId: string) => {
  const token = localStorage.getItem('access_token') || ''
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}/ws/etl/progress` +
    `?token=${encodeURIComponent(token)}&docId=${encodeURIComponent(docId)}`
}

export interface EtlProgress {
  docId: string
  stage: string
  documentCount: number
  chunkCount: number
  processedChunks: number
  percentage: number
}

export default api
