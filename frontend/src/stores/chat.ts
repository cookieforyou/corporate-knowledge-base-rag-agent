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
}

/**
 * 对话会话 store（3.15 多轮）：sessionId 前端自备（后端流式协议不回传，
 * AgentController 注释「sessionId 由前端自备」），跨页面导航保持会话；
 * 「新对话」重置 sessionId 与消息流（后端记忆随 sessionId 隔离）。
 */
export const useChatStore = defineStore('chat', () => {
  const sessionId = ref(crypto.randomUUID())
  const mode = ref<'rag' | 'tool'>('rag')
  const messages = ref<Message[]>([])

  function newSession() {
    sessionId.value = crypto.randomUUID()
    messages.value = []
  }

  return { sessionId, mode, messages, newSession }
})
