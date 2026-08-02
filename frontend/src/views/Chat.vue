<template>
  <div class="chat-page">
    <!-- ══ 页头 ══ -->
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">智能问答</h1>
        <p class="page-desc">基于企业知识库的溯源式对话——回答附 <b>[ref-N]</b> 证据引用</p>
      </div>
      <div class="head-badges">
        <span class="chip chip-pine">DeepSeek V4</span>
        <span class="chip chip-gold">双路混合检索</span>
        <span class="chip chip-rerank">qwen3-rerank 精排</span>
      </div>
    </header>

    <!-- ══ 对话区 ══ -->
    <div ref="msgList" class="msg-scroll">
      <!-- 空态：能力面板 -->
      <div v-if="messages.length === 0 && !streaming" class="empty reveal">
        <div class="empty-mark t-display">知</div>
        <h2 class="t-display empty-title">向知识库提问</h2>
        <p class="empty-sub">检索 · 融合 · 精排 · 溯源，全链路可观测</p>
        <div class="suggest">
          <button v-for="(s, i) in suggestions" :key="i" class="suggest-item panel panel-lift"
            :style="{ '--d': `${0.08 * i}s` }" @click="ask(s)">
            <span class="suggest-q">{{ s }}</span>
            <el-icon class="suggest-arrow"><Right /></el-icon>
          </button>
        </div>
        <div class="pipeline">
          <span class="pipe-step chip chip-vector">向量召回</span><i class="pipe-sep" />
          <span class="pipe-step chip chip-bm25">BM25 召回</span><i class="pipe-sep" />
          <span class="pipe-step chip chip-fusion">RRF 融合</span><i class="pipe-sep" />
          <span class="pipe-step chip chip-rerank">精排</span><i class="pipe-sep" />
          <span class="pipe-step chip chip-gold">Grounding 生成</span>
        </div>
      </div>

      <!-- 消息流 -->
      <div v-for="(msg, i) in messages" :key="i" :class="['msg', msg.role]">
        <div class="msg-avatar" :class="msg.role">
          {{ msg.role === 'user' ? '我' : '知' }}
        </div>
        <div class="msg-body">
          <div class="msg-bubble" :class="msg.role">
            <span class="msg-text" v-html="renderMsg(msg)" />
          </div>

          <!-- 溯源面板（assistant 且携带 trace） -->
          <div v-if="msg.role === 'assistant' && msg.sources?.length" class="trace-panel panel">
            <button class="trace-toggle" @click="msg.traceOpen = !msg.traceOpen">
              <el-icon><Document /></el-icon>
              <span>溯源 · {{ finalCount(msg) }} 条证据（{{ msg.sources.length }} 路检索）</span>
              <el-icon class="trace-caret" :class="{ open: msg.traceOpen }"><ArrowDown /></el-icon>
            </button>
            <transition name="slide-fade">
              <div v-if="msg.traceOpen" class="trace-list">
                <div v-for="src in msg.sources" :key="src.source" class="trace-group">
                  <div class="trace-group-head">
                    <span class="chip" :class="srcChipClass(src.source)">{{ srcLabel(src.source) }}</span>
                    <span class="trace-lat t-data" v-if="src.latencyMs != null">{{ src.latencyMs }} ms</span>
                  </div>
                  <div v-for="c in src.chunks.slice(0, 5)" :key="c.chunkId" class="trace-item">
                    <div class="trace-item-top">
                      <span class="t-data chunk-id">{{ (c.chunkId || '').slice(0, 8) }}</span>
                      <span v-if="c.fileName" class="chunk-file">{{ c.fileName }}</span>
                      <span class="trace-scores">
                        <i v-for="(v, k) in c.scores" :key="k" class="score-tag t-data"
                          :class="scoreClass(k)">{{ k }}={{ fmtScore(v) }}</i>
                      </span>
                    </div>
                    <div class="chunk-snippet">{{ c.snippet }}</div>
                  </div>
                </div>
              </div>
            </transition>
          </div>
        </div>
      </div>

      <!-- 流式占位 -->
      <div v-if="streaming" class="msg assistant">
        <div class="msg-avatar assistant">知</div>
        <div class="msg-body">
          <div class="msg-bubble assistant">
            <span class="msg-text">{{ streamText }}<i class="caret" /></span>
          </div>
        </div>
      </div>
    </div>

    <!-- ══ 上传进度条（2.13 实时 WS） ══ -->
    <transition name="slide-fade">
      <div v-if="upload" class="upload-strip panel reveal">
        <el-icon class="upload-ico"><Document /></el-icon>
        <div class="upload-info">
          <div class="upload-name">{{ upload.name }}
            <span class="chip" :class="upload.stage === 'FAILED' ? 'chip-danger' : 'chip-pine'">
              {{ stageLabel(upload.stage) }}
            </span>
          </div>
          <el-progress :percentage="Math.round(upload.percentage)" :status="progressStatus"
            :class="{ 'progress-live': isLiveStage(upload.stage) }" :stroke-width="8" />
        </div>
      </div>
    </transition>

    <!-- ══ 输入区 ══ -->
    <footer class="composer reveal">
      <div class="composer-box">
        <el-input v-model="input" type="textarea" :rows="2" resize="none"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          :disabled="streaming" @keydown.enter.exact="ask(input)" />
        <div class="composer-actions">
          <el-upload :show-file-list="false" :before-upload="handleUpload"
            accept=".pdf,.docx,.md,.txt,.html">
            <el-button text :disabled="streaming" class="attach-btn">
              <el-icon><Paperclip /></el-icon>&nbsp;上传文档
            </el-button>
          </el-upload>
          <el-button type="primary" round :disabled="!input.trim() || streaming" @click="ask(input)">
            发送&nbsp;<el-icon><Promotion /></el-icon>
          </el-button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { chatStreamUrl, uploadDocument, etlProgressWsUrl } from '@/api'
import type { EtlProgress } from '@/api'
import { ElMessage } from 'element-plus'
import { Right, ArrowDown, Document, Promotion, Link as Paperclip } from '@element-plus/icons-vue'

const auth = useAuthStore()

interface SourceChunk {
  chunkId: string
  fileName?: string | null
  pageNum?: number | null
  scores: Record<string, number>
  snippet: string
}
interface Source { source: string; chunks: SourceChunk[]; latencyMs?: number | null }
interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: Source[]
  traceOpen?: boolean
}

const messages = ref<Message[]>([])
const input = ref('')
const streamText = ref('')
const streaming = ref(false)
const msgList = ref<HTMLElement>()

const suggestions = [
  '什么是大泥球模式，DDD 建议怎么应对？',
  '实体和值对象应该如何区分？',
  '限界上下文为什么不能全局统一？'
]

function scrollToBottom() {
  nextTick(() => {
    if (msgList.value) msgList.value.scrollTop = msgList.value.scrollHeight
  })
}

/** [ref-N] → 高亮引用徽标（N 与溯源 final 序列下标对齐，11.1.2） */
function renderMsg(msg: Message) {
  const escaped = msg.content
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return escaped.replace(/\[ref-(\d+)\]/g, '<span class="ref-tag">ref-$1</span>')
}

async function ask(raw: string | undefined) {
  const query = (raw ?? '').trim()
  if (!query || streaming.value) return

  messages.value.push({ role: 'user', content: query })
  input.value = ''
  scrollToBottom()

  streaming.value = true
  streamText.value = ''
  let sources: Source[] = []

  try {
    const resp = await fetch(chatStreamUrl(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`
      },
      body: JSON.stringify({ query })
    })
    const reader = resp.body?.getReader()
    if (!reader) throw new Error('不支持流式响应')

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''   // SSE 命名事件（TRACE）

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
          continue
        }
        if (!line.startsWith('data:')) {
          if (line.trim() === '') currentEvent = ''
          continue
        }
        const data = line.slice(5).trim()
        if (data === '[DONE]') continue
        try {
          const json = JSON.parse(data)
          if (currentEvent === 'TRACE') {
            sources = json.sources || []
          } else if (json.token != null) {
            streamText.value += json.token
          } else if (json.error) {
            throw new Error(json.error)
          }
        } catch (e: any) {
          if (e instanceof SyntaxError) continue
          throw e
        }
        currentEvent = ''
      }
    }
    messages.value.push({
      role: 'assistant',
      content: streamText.value,
      sources,
      traceOpen: sources.length > 0
    })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', content: '请求失败：' + e.message })
  } finally {
    streaming.value = false
    streamText.value = ''
    scrollToBottom()
  }
}

// ── 上传 + ETL 实时进度（2.13）──

const upload = ref<{ name: string; stage: string; percentage: number } | null>(null)
let progressWs: WebSocket | null = null

async function handleUpload(file: File) {
  try {
    const docId = await uploadDocument(file)
    upload.value = { name: file.name, stage: 'UPLOADING', percentage: 5 }
    subscribeProgress(docId, file.name)
  } catch (e: any) {
    ElMessage.error('上传失败：' + (e.response?.data?.message || e.message))
  }
  return false
}

function subscribeProgress(docId: string, fileName: string) {
  progressWs?.close()
  const ws = new WebSocket(etlProgressWsUrl(docId))
  progressWs = ws
  ws.onmessage = ev => {
    try {
      const p: EtlProgress = JSON.parse(ev.data)
      if (p.docId !== docId) return
      upload.value = { name: fileName, stage: p.stage, percentage: p.percentage }
      if (p.stage === 'COMPLETED') {
        ElMessage.success(`「${fileName}」已完成入库，可立即检索`)
        closeProgress()
      } else if (p.stage === 'FAILED') {
        ElMessage.error(`「${fileName}」处理失败`)
        closeProgress()
      }
    } catch { /* 忽略非 JSON 帧 */ }
  }
  ws.onclose = () => { if (progressWs === ws) progressWs = null }
}

function closeProgress() {
  setTimeout(() => {
    progressWs?.close()
    progressWs = null
    upload.value = null
  }, 1600)
}

// ── 展示辅助 ──

const stageLabel = (s: string) =>
  ({ READING: '解析中', TRANSFORMING: '切分中', PERSISTING: '落库中',
     EMBEDDING: '向量化', INDEXING: '索引中', COMPLETED: '完成', FAILED: '失败' } as Record<string, string>)[s] || s

const isLiveStage = (s: string) => s !== 'COMPLETED' && s !== 'FAILED'

const progressStatus = computed(() => {
  if (!upload.value) return undefined
  if (upload.value.stage === 'COMPLETED') return 'success'
  if (upload.value.stage === 'FAILED') return 'exception'
  return undefined
})

const srcLabel = (s: string) =>
  ({ vector: '向量路', bm25: 'BM25 路', final: '最终注入' } as Record<string, string>)[s] || s

const srcChipClass = (s: string) =>
  ({ vector: 'chip-vector', bm25: 'chip-bm25', final: 'chip-rerank' } as Record<string, string>)[s] || 'chip-mute'

const scoreClass = (k: string) =>
  k.startsWith('bm25') ? 'sc-bm25' : k.startsWith('vector') || k === 'similarity'
    ? 'sc-vector' : k.startsWith('rerank') ? 'sc-rerank' : 'sc-fusion'

const fmtScore = (v: number) =>
  Math.abs(v) >= 10 ? v.toFixed(1) : Number(v).toFixed(3)

function finalCount(msg: Message) {
  return msg.sources?.find(s => s.source === 'final')?.chunks.length
    || msg.sources?.[0]?.chunks.length || 0
}
</script>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: 100%; }

/* ── 页头 ── */
.page-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  padding: 22px 28px 14px; gap: 16px;
}
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }
.page-desc b { color: var(--gold-600); }
.head-badges { display: flex; gap: 8px; flex-shrink: 0; }

/* ── 消息区 ── */
.msg-scroll { flex: 1; overflow-y: auto; padding: 8px 28px 16px; }

.empty { max-width: 620px; margin: 6vh auto 0; text-align: center; }
.empty-mark {
  width: 56px; height: 56px; margin: 0 auto 16px; border-radius: 16px;
  display: grid; place-items: center; font-size: 27px;
  color: var(--gold-100);
  background: linear-gradient(150deg, var(--pine-600), var(--pine-900));
  box-shadow: 0 8px 24px rgba(13, 43, 37, .22);
}
.empty-title { margin: 0; font-size: 26px; color: var(--pine-900); }
.empty-sub { margin: 8px 0 22px; color: var(--ink-3); }
.suggest { display: flex; flex-direction: column; gap: 10px; }
.suggest-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 13px 18px; cursor: pointer; text-align: left;
  font-size: 14px; color: var(--ink-2); font-family: var(--font-body);
  animation: fade-up .5s var(--ease) both; animation-delay: var(--d);
}
.suggest-arrow { color: var(--ink-3); transition: transform .25s var(--ease), color .25s; }
.suggest-item:hover .suggest-arrow { transform: translateX(4px); color: var(--pine-700); }
.pipeline {
  margin-top: 26px; display: flex; align-items: center; justify-content: center;
  gap: 6px; flex-wrap: wrap;
}
.pipe-sep { width: 16px; height: 1px; background: var(--line-strong); }

.msg { display: flex; gap: 12px; margin-bottom: 22px; animation: fade-up .4s var(--ease) both; }
.msg.user { flex-direction: row-reverse; }
.msg-avatar {
  width: 34px; height: 34px; border-radius: 10px; flex-shrink: 0;
  display: grid; place-items: center; font-size: 14px; font-weight: 700;
  font-family: var(--font-display);
}
.msg-avatar.user { background: var(--pine-700); color: #EAF2EF; }
.msg-avatar.assistant {
  background: linear-gradient(150deg, var(--gold-500), #A9781F); color: #1C2725;
}
.msg-body { max-width: 76%; display: flex; flex-direction: column; gap: 8px; }
.msg.user .msg-body { align-items: flex-end; }
.msg-bubble {
  padding: 12px 16px; border-radius: 13px; line-height: 1.75;
  white-space: pre-wrap; word-break: break-word; font-size: 14px;
  border: 1px solid transparent;
}
.msg-bubble.user {
  background: var(--pine-700); color: #F0F6F4;
  border-radius: 13px 13px 4px 13px;
}
.msg-bubble.assistant {
  background: var(--surface); border-color: var(--line);
  border-radius: 13px 13px 13px 4px; box-shadow: var(--shadow-sm);
}
.msg-text :deep(.ref-tag) {
  display: inline-block; padding: 0 6px; margin: 0 2px; border-radius: 5px;
  background: var(--gold-100); color: var(--gold-600);
  font-family: var(--font-data); font-size: 11.5px; font-weight: 600;
  border: 1px solid #EBD9B4; vertical-align: 1px; cursor: default;
}

/* ── 溯源面板 ── */
.trace-panel { overflow: hidden; }
.trace-toggle {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 9px 14px; border: none; background: var(--surface-2); cursor: pointer;
  font-size: 12.5px; font-weight: 600; color: var(--pine-700);
  font-family: var(--font-body);
  transition: background .2s;
}
.trace-toggle:hover { background: var(--pine-50); }
.trace-caret { transition: transform .25s var(--ease); margin-left: auto; }
.trace-caret.open { transform: rotate(180deg); }
.trace-list { border-top: 1px solid var(--line); }
.trace-group { padding: 10px 14px; border-bottom: 1px dashed var(--line); }
.trace-group:last-child { border-bottom: none; }
.trace-group-head { display: flex; align-items: center; gap: 8px; margin-bottom: 7px; }
.trace-lat { font-size: 11px; color: var(--ink-3); }
.trace-item { padding: 6px 0; }
.trace-item-top {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 3px;
}
.chunk-id {
  font-size: 11px; color: var(--ink-3); background: #EEF1F0;
  padding: 1px 6px; border-radius: 4px;
}
.chunk-file { font-size: 11.5px; color: var(--ink-2); }
.trace-scores { display: inline-flex; gap: 4px; flex-wrap: wrap; margin-left: auto; }
.score-tag {
  font-style: normal; font-size: 10.5px; padding: 1px 5px; border-radius: 4px;
}
.sc-vector { background: #E8F1F8; color: var(--c-vector); }
.sc-bm25   { background: #FAEFE4; color: var(--c-bm25); }
.sc-fusion { background: var(--pine-50); color: var(--c-fusion); }
.sc-rerank { background: #F7EAEF; color: var(--c-rerank); }
.chunk-snippet {
  font-size: 12px; color: var(--ink-3); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

/* ── 上传进度 ── */
.upload-strip {
  display: flex; align-items: center; gap: 12px;
  margin: 0 28px 10px; padding: 12px 16px;
  border-left: 3px solid var(--pine-600);
}
.upload-ico { color: var(--pine-700); font-size: 18px; }
.upload-info { flex: 1; }
.upload-name {
  font-size: 13px; font-weight: 600; color: var(--ink-2);
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
}

/* ── 输入区 ── */
.composer { padding: 0 28px 22px; }
.composer-box {
  background: var(--surface); border: 1px solid var(--line);
  border-radius: var(--radius-lg); box-shadow: var(--shadow-md);
  padding: 10px 12px 8px; transition: border-color .2s;
}
.composer-box:focus-within { border-color: var(--pine-600); }
.composer-box :deep(.el-textarea__inner) {
  box-shadow: none !important; padding: 4px 6px; background: transparent;
}
.composer-actions { display: flex; justify-content: space-between; align-items: center; }
.attach-btn { color: var(--ink-3); }
.attach-btn:hover { color: var(--pine-700); }

.slide-fade-enter-active { transition: all .3s var(--ease); }
.slide-fade-leave-active { transition: all .2s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
