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
