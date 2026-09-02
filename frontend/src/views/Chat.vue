<template>
  <div class="chat-shell">
    <!-- 历史会话栏（3.15 补齐：打开历史会话可溯源并续聊） -->
    <SessionList ref="sessionListRef" :active-id="store.sessionId" :disabled="streaming"
      @select="openHistory" @deleted="onSessionDeleted" />
    <div class="chat-page">
    <!-- ══ 页头 ══ -->
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">智能问答</h1>
        <p class="page-desc">基于企业知识库的溯源式对话——回答附 <b>[ref-N]</b> 证据引用，点击可查原文</p>
      </div>
      <div class="head-badges">
        <span class="chip chip-pine">GLM-5.3-Flash</span>
        <span class="chip chip-gold">多路混合检索</span>
        <span class="chip chip-rerank">qwen3-rerank 精排</span>
        <el-button size="small" round :disabled="streaming" @click="newChat">
          <el-icon><Refresh /></el-icon>&nbsp;新对话
        </el-button>
      </div>
    </header>

    <!-- ══ 对话区 ══ -->
    <div ref="msgList" class="msg-scroll">
      <!-- 空态：能力面板 -->
      <div v-if="store.messages.length === 0 && !streaming" class="empty reveal">
        <div class="empty-mark t-display">知</div>
        <h2 class="t-display empty-title">{{ store.mode === 'rag' ? '向知识库提问' : '企业事务办理' }}</h2>
        <p class="empty-sub">{{ store.mode === 'rag'
          ? '检索 · 融合 · 精排 · 溯源，全链路可观测'
          : '员工查询 · 年假余额 · 请假申请（写操作需人工审批）' }}</p>
        <div class="suggest">
          <button v-for="(s, i) in suggestions" :key="i" class="suggest-item panel panel-lift"
            :style="{ '--d': `${0.08 * i}s` }" @click="ask(s)">
            <span class="suggest-q">{{ s }}</span>
            <el-icon class="suggest-arrow"><Right /></el-icon>
          </button>
        </div>
        <div class="pipeline">
          <template v-if="store.mode === 'rag'">
            <span class="pipe-step chip chip-vector">向量召回</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-bm25">BM25 召回</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-graph">图谱召回</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-fusion">RRF 融合</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-rerank">精排</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-gold">Grounding 生成</span>
          </template>
          <template v-else>
            <span class="pipe-step chip chip-pine">读工具自动执行</span><i class="pipe-sep" />
            <span class="pipe-step chip chip-gold">写工具 HITL 审批</span>
          </template>
        </div>
      </div>

      <!-- 消息流 -->
      <div v-for="(msg, i) in store.messages" :key="i" :class="['msg', msg.role]">
        <div class="msg-avatar" :class="msg.role">
          {{ msg.role === 'user' ? '我' : '知' }}
        </div>
        <div class="msg-body">
          <div class="msg-bubble" :class="msg.role">
            <span v-if="msg.role === 'user'" class="msg-text" v-html="escapeHtml(msg.content)" />
            <span v-else class="msg-text md" v-html="renderAnswer(msg.content)"
              @click="onBubbleClick(msg, $event)" />
          </div>

          <!-- 工具调用卡片（tool 链 TOOL_CALL 事件，3.15） -->
          <ToolCallCard v-for="(tc, j) in msg.toolCalls" :key="j" :call="tc"
            @confirmed="onApprovalConfirmed" />

          <!-- 溯源面板（assistant 且携带 trace） -->
          <div v-if="msg.role === 'assistant' && msg.sources?.length" class="trace-panel panel">
            <button class="trace-toggle" @click="msg.traceOpen = !msg.traceOpen">
              <el-icon><Document /></el-icon>
              <span>溯源 · {{ finalCount(msg) }} 条证据（多路检索）</span>
              <el-icon class="trace-caret" :class="{ open: msg.traceOpen }"><ArrowDown /></el-icon>
            </button>
            <transition name="slide-fade">
              <div v-if="msg.traceOpen" class="trace-list">
                <div v-for="src in msg.sources" :key="src.source" class="trace-group">
                  <div class="trace-group-head">
                    <span class="chip" :class="srcChipClass(src.source)">{{ srcLabel(src.source) }}</span>
                    <span class="trace-lat t-data" v-if="src.latencyMs != null">{{ src.latencyMs }} ms</span>
                  </div>
                  <div v-for="c in src.chunks.slice(0, 5)" :key="c.chunkId"
                    class="trace-item" :class="{ clickable: !!c.docId }"
                    :title="c.docId ? '点击查看原文' : ''" @click="openSource(c)">
                    <div class="trace-item-top">
                      <span class="t-data chunk-id">{{ (c.chunkId || '').slice(0, 8) }}</span>
                      <span v-if="c.fileName" class="chunk-file">{{ c.fileName }}</span>
                      <span v-if="c.pageNum" class="chunk-file t-data">p.{{ c.pageNum }}</span>
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

          <!-- 用户反馈（3.17）：👍/👎 → kb_feedback 落库；messageId 来自 DONE 帧，
               缺失（错误轮/旧数据）不渲染。👎 展开期望回答表单（Bad Case 进评估闭环） -->
          <div v-if="msg.role === 'assistant' && msg.messageId" class="feedback-row">
            <button class="fb-btn" :class="{ active: msg.feedback === 'POSITIVE' }"
              :disabled="msg.feedbackBusy" title="回答准确"
              @click="submitRate(msg, 'POSITIVE')">👍</button>
            <button class="fb-btn" :class="{ active: msg.feedback === 'NEGATIVE' }"
              :disabled="msg.feedbackBusy" title="回答待改进"
              @click="toggleDislikeForm(msg)">👎</button>
          </div>
          <div v-if="dislikeTarget === msg" class="dislike-form panel">
            <el-input v-model="dislikeText" type="textarea" :rows="2" resize="none"
              placeholder="期望回答是什么？（可选，Bad Case 将进入评估闭环）" />
            <div class="dislike-tags">
              <button v-for="t in FEEDBACK_TAGS" :key="t" class="tag-opt"
                :class="{ on: dislikeTags.includes(t) }" @click="toggleTag(t)">{{ t }}</button>
            </div>
            <div class="dislike-actions">
              <el-button size="small" text @click="dislikeTarget = null">取消</el-button>
              <el-button size="small" type="primary" :disabled="msg.feedbackBusy"
                @click="submitRate(msg, 'NEGATIVE', dislikeText, dislikeTags)">提交反馈</el-button>
            </div>
          </div>
        </div>
      </div>

      <!-- 流式占位 -->
      <div v-if="streaming" class="msg assistant">
        <div class="msg-avatar assistant">知</div>
        <div class="msg-body">
          <div class="msg-bubble assistant">
            <span class="msg-text md" v-html="renderAnswer(streamText)" /><i class="caret" />
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
          :placeholder="store.mode === 'rag'
            ? '输入问题，Enter 发送，Shift+Enter 换行'
            : '描述要办理的事务，Enter 发送（写操作将弹出审批确认）'"
          :disabled="streaming" @keydown.enter.exact="ask(input)" />
        <div class="composer-actions">
          <div class="composer-left">
            <!-- 双链路显式分流（11.5）：rag 知识问答 / tool 企业事务 -->
            <el-radio-group v-model="store.mode" size="small" :disabled="streaming">
              <el-radio-button value="rag">知识问答</el-radio-button>
              <el-radio-button value="tool">企业工具</el-radio-button>
            </el-radio-group>
            <el-upload :show-file-list="false" :before-upload="handleUpload"
              accept=".pdf,.docx,.pptx,.xlsx,.md,.txt,.html">
              <el-button text :disabled="streaming" class="attach-btn">
                <el-icon><Paperclip /></el-icon>&nbsp;上传文档
              </el-button>
            </el-upload>
          </div>
          <el-button type="primary" round :disabled="!input.trim() || streaming" @click="ask(input)">
            发送&nbsp;<el-icon><Promotion /></el-icon>
          </el-button>
        </div>
      </div>
    </footer>

    <!-- 原文对话框（3.15 验收：溯源引用可点击查看原文） -->
    <SourceDialog :target="sourceTarget" @close="sourceTarget = null" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useChatStore, type Message, type Source, type SourceChunk } from '@/stores/chat'
import { chatStreamUrl, uploadDocument, etlProgressWsUrl, submitFeedback, getSessionMessages,
  type ToolCallInfo, type EtlProgress, type HistoryMessage } from '@/api'
import { renderAnswer } from '@/composables/markdown'
import SourceDialog, { type SourceTarget } from '@/components/SourceDialog.vue'
import ToolCallCard from '@/components/ToolCallCard.vue'
import SessionList from '@/components/SessionList.vue'
import { ElMessage } from 'element-plus'
import { Right, ArrowDown, Document, Promotion, Refresh, Link as Paperclip } from '@element-plus/icons-vue'

const auth = useAuthStore()
const store = useChatStore()

const input = ref('')
const streamText = ref('')
const streaming = ref(false)
const msgList = ref<HTMLElement>()
const sourceTarget = ref<SourceTarget | null>(null)
const sessionListRef = ref<InstanceType<typeof SessionList>>()

const suggestionsByMode: Record<'rag' | 'tool', string[]> = {
  rag: [
    '什么是大泥球模式，DDD 建议怎么应对？',
    '实体和值对象应该如何区分？',
    '限界上下文为什么不能全局统一？'
  ],
  tool: [
    '查询员工 E1001 的基本信息',
    '员工 E1001 还剩多少天年假？',
    '帮张三提交 2026-08-10 到 2026-08-12 的年假申请'
  ]
}
const suggestions = computed(() => suggestionsByMode[store.mode])

function scrollToBottom() {
  nextTick(() => {
    if (msgList.value) msgList.value.scrollTop = msgList.value.scrollHeight
  })
}

function escapeHtml(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function newChat() {
  store.newSession()
  sourceTarget.value = null
}

/** 打开历史会话（3.15 补齐）：拉 PG 归档消息映射为 Message[]——sources 即 citations、
 *  messageId 即 kb_message.id（反馈链路复用）、feedback 回显；sessionId 续用即真续聊 */
async function openHistory(id: string) {
  if (streaming.value || id === store.sessionId) return
  try {
    const history = await getSessionMessages(id)
    store.openSession(id, history.map(toMessage))
    sourceTarget.value = null
    scrollToBottom()
  } catch (e: any) {
    ElMessage.error('打开会话失败：' + (e.response?.data?.message || e.message))
  }
}

function toMessage(m: HistoryMessage): Message {
  if (m.role === 'USER') return { role: 'user', content: m.content }
  return {
    role: 'assistant',
    content: m.content,
    sources: m.sources ?? undefined,
    traceOpen: !!m.sources?.length,   // 与实时轮行为一致：有溯源即展开面板
    messageId: m.id,
    traceId: m.traceId ?? undefined,
    feedback: m.feedback ?? undefined
  }
}

/** 删除联动：删的是当前会话 → 回新对话态 */
function onSessionDeleted(id: string) {
  if (id === store.sessionId) newChat()
}

/** [ref-N] 点击（事件委托）：N 与溯源 final 序列下标对齐（11.1.2），打开原文对话框 */
function onBubbleClick(msg: Message, e: MouseEvent) {
  const el = (e.target as HTMLElement).closest('[data-ref]') as HTMLElement | null
  if (!el) return
  const final = msg.sources?.find(s => s.source === 'final')
  const chunk = final?.chunks[Number(el.dataset.ref) - 1]
  if (!chunk) {
    ElMessage.info('溯源数据未就绪')
    return
  }
  msg.traceOpen = true
  openSource(chunk)
}

/** 溯源条目 / ref 徽标 → 原文对话框（docId 缺失为旧数据形态，降级为不可点） */
function openSource(c: SourceChunk) {
  if (!c.docId) return
  sourceTarget.value = {
    chunkId: c.chunkId, docId: c.docId,
    fileName: c.fileName, pageNum: c.pageNum
  }
}

/** HITL 批准后自动发起确认轮（11.2.1 三段式第三段）：携带 approvedToolCallId，
 *  后端注入确认指令使写工具复调确定化；确认轮必走 tool 链 */
function onApprovalConfirmed(approvalId: string) {
  ask('确认执行上述操作', { mode: 'tool', approvedToolCallId: approvalId })
}

// ── 用户反馈（3.17）──

const FEEDBACK_TAGS = ['答案不准确', '引用不相关', '答非所问', '内容不完整']
const dislikeTarget = ref<Message | null>(null)
const dislikeText = ref('')
const dislikeTags = ref<string[]>([])

function toggleDislikeForm(msg: Message) {
  if (dislikeTarget.value === msg) {
    dislikeTarget.value = null
    return
  }
  dislikeTarget.value = msg
  dislikeText.value = ''
  dislikeTags.value = []
}

function toggleTag(tag: string) {
  const i = dislikeTags.value.indexOf(tag)
  if (i >= 0) dislikeTags.value.splice(i, 1)
  else dislikeTags.value.push(tag)
}

/** 提交反馈（upsert 语义，可更改评价）；POSITIVE 直提，NEGATIVE 经表单携带期望回答 */
async function submitRate(msg: Message, rating: 'POSITIVE' | 'NEGATIVE',
                          expectedAnswer?: string, tags?: string[]) {
  if (!msg.messageId || msg.feedbackBusy) return
  msg.feedbackBusy = true
  try {
    await submitFeedback({
      messageId: msg.messageId,
      traceId: msg.traceId,
      rating,
      expectedAnswer: expectedAnswer?.trim() || null,
      tags: tags?.length ? [...tags] : undefined
    })
    msg.feedback = rating
    dislikeTarget.value = null
    ElMessage.success(rating === 'POSITIVE' ? '感谢反馈' : '已收到反馈，将持续改进')
  } catch (e: any) {
    ElMessage.error('反馈提交失败：' + (e.response?.data?.message || e.message))
  } finally {
    msg.feedbackBusy = false
  }
}

interface AskOpts { mode?: 'rag' | 'tool'; approvedToolCallId?: string }

async function ask(raw: string | undefined, opts: AskOpts = {}) {
  const query = (raw ?? '').trim()
  if (!query || streaming.value) return

  const mode = opts.mode ?? store.mode
  store.messages.push({ role: 'user', content: query })
  input.value = ''
  scrollToBottom()

  streaming.value = true
  streamText.value = ''
  let sources: Source[] = []
  let toolCalls: ToolCallInfo[] = []
  // DONE 帧 JSON 载荷（3.17）：本轮反馈定位句柄
  let doneMeta: { messageId?: string; traceId?: string } = {}

  try {
    const resp = await fetch(chatStreamUrl(), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${auth.token}`
      },
      // 会话协议（3.1/11.5）：sessionId 前端自备多轮回传；mode 双链显式分流；
      // approvedToolCallId 仅 tool 链 HITL 确认轮携带
      body: JSON.stringify({
        query,
        sessionId: store.sessionId,
        mode,
        ...(opts.approvedToolCallId ? { approvedToolCallId: opts.approvedToolCallId } : {})
      })
    })
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`
      try {
        const j = await resp.json()
        msg = j.message || j.error || msg
      } catch { /* 非 JSON 错误体 */ }
      throw new Error(msg)
    }
    const reader = resp.body?.getReader()
    if (!reader) throw new Error('不支持流式响应')

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = ''   // SSE 命名事件（TRACE / TOOL_CALL）

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
        if (data === '[DONE]') continue   // 旧协议字面量兼容（3.17 起为 JSON 载荷）
        try {
          const json = JSON.parse(data)
          if (currentEvent === 'TRACE') {
            sources = json.sources || []
          } else if (currentEvent === 'TOOL_CALL') {
            toolCalls = json.toolCalls || []
          } else if (json.messageId != null) {
            // DONE 帧（3.17）：{messageId, traceId} 反馈定位句柄
            doneMeta = { messageId: json.messageId, traceId: json.traceId }
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
    store.messages.push({
      role: 'assistant',
      content: streamText.value,
      sources,
      traceOpen: sources.length > 0,
      toolCalls: toolCalls.length ? toolCalls : undefined,
      messageId: doneMeta.messageId,
      traceId: doneMeta.traceId
    })
  } catch (e: any) {
    store.messages.push({ role: 'assistant', content: '请求失败：' + e.message })
  } finally {
    streaming.value = false
    streamText.value = ''
    scrollToBottom()
    // 归档为异步旁路：DONE 后延迟刷新会话列表，防首轮会话未落库的竞态空窗
    setTimeout(() => sessionListRef.value?.refresh(), 1000)
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
  ({ vector: '向量路', bm25: 'BM25 路', graph: '图谱路', final: '最终注入' } as Record<string, string>)[s] || s

const srcChipClass = (s: string) =>
  ({ vector: 'chip-vector', bm25: 'chip-bm25', graph: 'chip-graph', final: 'chip-rerank' } as Record<string, string>)[s] || 'chip-mute'

const scoreClass = (k: string) =>
  k.startsWith('bm25') ? 'sc-bm25' : k.startsWith('vector') || k === 'similarity'
    ? 'sc-vector' : k.startsWith('graph') ? 'sc-graph'
      : k.startsWith('rerank') ? 'sc-rerank' : 'sc-fusion'

const fmtScore = (v: number) =>
  Math.abs(v) >= 10 ? v.toFixed(1) : Number(v).toFixed(3)

function finalCount(msg: Message) {
  return msg.sources?.find(s => s.source === 'final')?.chunks.length
    || msg.sources?.[0]?.chunks.length || 0
}
</script>

<style scoped>
.chat-shell { display: flex; height: 100%; }
.chat-page { flex: 1; min-width: 0; display: flex; flex-direction: column; height: 100%; }

/* ── 页头 ── */
.page-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  padding: 22px 28px 14px; gap: 16px;
}
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }
.page-desc b { color: var(--gold-600); }
.head-badges { display: flex; gap: 8px; flex-shrink: 0; align-items: center; }

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
  word-break: break-word; font-size: 14px;
  border: 1px solid transparent;
}
.msg-bubble.user {
  background: var(--pine-700); color: #F0F6F4; white-space: pre-wrap;
  border-radius: 13px 13px 4px 13px;
}
.msg-bubble.assistant {
  background: var(--surface); border-color: var(--line);
  border-radius: 13px 13px 13px 4px; box-shadow: var(--shadow-sm);
}

/* ── markdown 渲染（assistant 气泡，3.15）── */
.msg-text.md :deep(p) { margin: 0 0 .55em; }
.msg-text.md :deep(p:last-child) { margin-bottom: 0; }
.msg-text.md :deep(ul), .msg-text.md :deep(ol) { margin: .3em 0 .6em; padding-left: 1.5em; }
.msg-text.md :deep(li) { margin: .15em 0; }
.msg-text.md :deep(h1), .msg-text.md :deep(h2), .msg-text.md :deep(h3),
.msg-text.md :deep(h4) { margin: .6em 0 .35em; font-size: 1.05em; color: var(--pine-900); }
.msg-text.md :deep(pre) {
  margin: .5em 0; padding: 10px 12px; overflow-x: auto;
  background: #10201C; color: #D7E5E0; border-radius: 8px;
  font-size: 12.5px; line-height: 1.6;
}
.msg-text.md :deep(code) {
  font-family: var(--font-data); font-size: .92em;
  background: var(--gold-100); color: var(--gold-600);
  padding: 1px 5px; border-radius: 4px;
}
.msg-text.md :deep(pre code) { background: none; color: inherit; padding: 0; }
.msg-text.md :deep(table) { border-collapse: collapse; margin: .5em 0; font-size: 13px; }
.msg-text.md :deep(th), .msg-text.md :deep(td) {
  border: 1px solid var(--line-strong); padding: 4px 10px;
}
.msg-text.md :deep(blockquote) {
  margin: .4em 0; padding: 2px 12px; border-left: 3px solid var(--gold-500);
  color: var(--ink-3);
}

/* ── [ref-N] 引用徽标（可点击查原文，3.15）── */
.msg-text :deep(.ref-tag) {
  display: inline-block; padding: 0 6px; margin: 0 2px; border-radius: 5px;
  background: var(--gold-100); color: var(--gold-600);
  font-family: var(--font-data); font-size: 11.5px; font-weight: 600;
  border: 1px solid #EBD9B4; vertical-align: 1px; cursor: pointer;
  transition: background .2s, transform .15s;
}
.msg-text :deep(.ref-tag:hover) {
  background: var(--gold-500); color: #fff; transform: translateY(-1px);
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
.trace-item { padding: 6px 8px; margin: 0 -8px; border-radius: 8px; }
.trace-item.clickable { cursor: pointer; transition: background .2s; }
.trace-item.clickable:hover { background: var(--pine-50); }
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
.sc-graph  { background: #EAF4EC; color: #2F7D4F; }
.sc-fusion { background: var(--pine-50); color: var(--c-fusion); }
.sc-rerank { background: #F7EAEF; color: var(--c-rerank); }
.chunk-snippet {
  font-size: 12px; color: var(--ink-3); line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

/* ── 用户反馈（3.17）── */
.feedback-row { display: flex; gap: 6px; }
.fb-btn {
  width: 30px; height: 26px; border-radius: 7px; cursor: pointer;
  border: 1px solid var(--line); background: var(--surface);
  font-size: 13px; line-height: 1; display: grid; place-items: center;
  opacity: .55; transition: all .2s var(--ease);
}
.fb-btn:hover:not(:disabled) { opacity: 1; border-color: var(--pine-600); transform: translateY(-1px); }
.fb-btn:disabled { cursor: default; }
.fb-btn.active { opacity: 1; background: var(--gold-100); border-color: var(--gold-500); }
.dislike-form { padding: 12px 14px; display: flex; flex-direction: column; gap: 10px; }
.dislike-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tag-opt {
  padding: 3px 10px; border-radius: 20px; font-size: 12px; cursor: pointer;
  border: 1px solid var(--line-strong); background: var(--surface);
  color: var(--ink-3); font-family: var(--font-body); transition: all .2s;
}
.tag-opt.on { background: var(--pine-700); border-color: var(--pine-700); color: #EAF2EF; }
.dislike-actions { display: flex; justify-content: flex-end; gap: 4px; }

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
.composer-left { display: flex; align-items: center; gap: 10px; }
.attach-btn { color: var(--ink-3); }
.attach-btn:hover { color: var(--pine-700); }

.slide-fade-enter-active { transition: all .3s var(--ease); }
.slide-fade-leave-active { transition: all .2s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { opacity: 0; transform: translateY(-6px); }
</style>
