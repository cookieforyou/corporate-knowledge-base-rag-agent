import axios from 'axios'
import router from '@/router'

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

export const uploadDocument = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api.post('/documents/upload', form).then(r => r.data.data.docId as string)
}

export const listDocuments = () =>
  api.get('/documents').then(r => r.data.data as KbDoc[])

export const getChunks = (docId: string) =>
  api.get(`/documents/${docId}/chunks`).then(r => r.data.data as KbChunk[])

export const deleteDocument = (docId: string) =>
  api.delete(`/documents/${docId}`).then(r => r.data.data)

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
