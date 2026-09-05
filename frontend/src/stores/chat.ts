import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { ToolCallInfo } from '@/api'

/** 溯源 chunk 轻量投影（SSE TRACE 帧，全文经 getChunks 按需拉取，3.15） */
export interface SourceChunk {
  chunkId: string
  docId?: string | null
  fileName?: string | null
  pageNum?: number | null
  scores: Record<string, number>
  snippet: string
}

export interface Source {
  source: string
  chunks: SourceChunk[]
  latencyMs?: number | null
}

export interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  traceOpen?: boolean
  toolCalls?: ToolCallInfo[]
  /** 反馈定位句柄（3.17）：SSE DONE 帧 JSON 载荷送达；缺失则不渲染反馈按钮 */
  messageId?: string
  traceId?: string
  /** 已提交的反馈评分（upsert 语义，可更改） */
  feedback?: 'POSITIVE' | 'NEGATIVE'
  /** 反馈提交中（按钮禁用防重复） */
  feedbackBusy?: boolean
}

/**
 * 对话会话 store（3.15 多轮）：sessionId 前端自备（后端流式协议不回传，
 * AgentController 注释「sessionId 由前端自备」），跨页面导航保持会话；
 * 「新对话」重置 sessionId 与消息流（后端记忆随 sessionId 隔离）。
 */
export const useChatStore = defineStore('chat', () => {
  const sessionId = ref<string>(crypto.randomUUID())
  const mode = ref<'rag' | 'tool' | 'agent'>('rag')
  const messages = ref<Message[]>([])

  function newSession() {
    sessionId.value = crypto.randomUUID()
    messages.value = []
  }

  /** 打开历史会话（3.15 补齐）：整替 sessionId 与消息流，续聊沿用同一 ID */
  function openSession(id: string, loaded: Message[]) {
    sessionId.value = id
    messages.value = loaded
  }

  return { sessionId, mode, messages, newSession, openSession }
})
