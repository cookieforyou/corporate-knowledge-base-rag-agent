<template>
  <div class="docs-page">
    <!-- ══ 页头 ══ -->
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">文档管理</h1>
        <p class="page-desc">知识资产全生命周期：上传 → 解析 → 切分 → 向量化 → 双写索引</p>
      </div>
      <div class="head-stats">
        <div class="stat">
          <span class="stat-num t-data">{{ docs.length }}</span>
          <span class="t-label">文档</span>
        </div>
        <div class="stat">
          <span class="stat-num t-data">{{ totalChunks }}</span>
          <span class="t-label">Chunks</span>
        </div>
        <el-button type="primary" round @click="uploadOpen = true">
          <el-icon><UploadFilled /></el-icon>&nbsp;上传文档
        </el-button>
      </div>
    </header>

    <!-- ══ 处理中横幅（WS 实时进度，2.13） ══ -->
    <transition-group name="slide-fade" tag="div" class="live-list">
      <div v-for="(p, docId) in liveProgress" :key="docId" class="live-card panel">
        <div class="live-head">
          <el-icon class="live-ico"><Loading /></el-icon>
          <span class="live-name">{{ p.name }}</span>
          <span class="chip" :class="p.stage === 'FAILED' ? 'chip-danger' : 'chip-gold'">
            {{ stageLabel(p.stage) }}
          </span>
        </div>
        <el-progress :percentage="Math.round(p.percentage)" :stroke-width="9"
          :status="p.stage === 'FAILED' ? 'exception' : (p.stage === 'COMPLETED' ? 'success' : undefined)"
          :class="{ 'progress-live': isLiveStage(p.stage) }" />
      </div>
    </transition-group>

    <!-- ══ 文档表格 ══ -->
    <div class="table-wrap panel reveal" style="--d:.08s">
      <el-table :data="docs" v-loading="loading" empty-text="暂无文档——点击右上角上传第一份知识资产"
        :header-cell-style="{ background: 'var(--surface-2)' }" row-class-name="doc-row" stripe>
        <el-table-column label="文档" min-width="240">
          <template #default="{ row }">
            <div class="doc-cell">
              <span class="doc-ico" :class="'ico-' + (row.type || 'UNKNOWN').toLowerCase()">
                {{ typeShort(row.type) }}
              </span>
              <div class="doc-names">
                <span class="doc-name">{{ row.name }}</span>
                <span class="t-data doc-meta">v{{ row.version ?? 1 }} · {{ fmtSize(row.size) }} · {{ fmtTime(row.updatedAt) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span class="chip" :class="statusChip(row.status)">
              <i v-if="isLiveDocStatus(row.status)" class="mini-pulse" />
              {{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="解析路由" width="110">
          <template #default="{ row }">
            <span v-if="row.parseRoute" class="chip chip-mute">{{ row.parseRoute }}</span>
            <span v-else class="doc-dim">—</span>
          </template>
        </el-table-column>
        <el-table-column label="Chunks" width="100" align="right">
          <template #default="{ row }">
            <span class="t-data chunk-num">{{ row.chunkCount ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="252" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" :disabled="row.status !== 'SUCCESS'"
              @click="openChunks(row)">
              <el-icon><Grid /></el-icon>&nbsp;Chunks
            </el-button>
            <el-button size="small" text type="warning" :disabled="!canReindex(row.status)"
              @click="confirmReparse(row)">
              <el-icon><RefreshRight /></el-icon>&nbsp;重解析
            </el-button>
            <el-button size="small" text type="warning" :disabled="!canReindex(row.status)"
              @click="confirmReplace(row)">
              <el-icon><UploadFilled /></el-icon>&nbsp;替换
            </el-button>
            <!-- 处理期禁删（与后端 DOC_NOT_READY 守卫同状态集，簇⑥ C1 收尾） -->
            <el-button size="small" text type="danger" :disabled="isLiveDocStatus(row.status)"
              @click="confirmDelete(row)">
              <el-icon><Delete /></el-icon>&nbsp;删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ══ 上传对话框 ══ -->
    <el-dialog v-model="uploadOpen" title="上传知识文档" width="480" :close-on-click-modal="false">
      <div class="route-row">
        <span class="route-label">解析路由</span>
        <el-select v-model="parseRouteChoice" style="width: 300px">
          <el-option label="自动决策（文本密度探测）" value="" />
          <el-option label="NATIVE · Tika 原生解析" value="NATIVE" />
          <el-option label="DEEP · DocMind 大模型解析" value="DEEP" />
          <el-option label="OCR · qwen3.5-ocr 视觉识别" value="OCR" />
        </el-select>
      </div>
      <el-upload drag multiple :show-file-list="true" :before-upload="handleUpload"
        accept=".pdf,.docx,.md,.txt,.html" class="upload-zone">
        <el-icon class="upload-cloud"><UploadFilled /></el-icon>
        <div class="upload-hint">拖拽文件到此处，或<em>点击选择</em></div>
        <div class="upload-formats">支持 PDF · DOCX · Markdown · TXT · HTML</div>
      </el-upload>
      <div class="upload-note">上传后自动进入 ETL 管道（解析 → 切分 → 向量化 → ES 双写），进度实时显示在页面顶部。</div>
    </el-dialog>

    <!-- 替换文件选择器（隐藏 input，簇⑥ C1） -->
    <input ref="replaceInput" type="file" accept=".pdf,.docx,.md,.txt,.html"
      style="display:none" @change="onReplaceFile" />

    <!-- ══ Chunk 抽屉 ══ -->
    <el-drawer v-model="chunkOpen" size="560" :title="`${activeDoc?.name ?? ''} · Chunk 观测`">
      <div v-if="chunks.length" class="chunk-meta-row">
        <span class="chip chip-pine">{{ chunks.length }} chunks</span>
        <span class="chip chip-mute">切分 800 tokens / overlap 200</span>
      </div>
      <div v-loading="chunksLoading" class="chunk-list">
        <div v-for="(c, i) in chunks" :key="c.id" class="chunk-card panel panel-lift reveal"
          :style="{ '--d': `${Math.min(i * 0.04, 0.4)}s` }">
          <div class="chunk-head">
            <span class="chunk-idx t-data">#{{ c.chunkIndex }}</span>
            <span class="chip chip-mute">{{ c.chunkType }}</span>
            <span v-if="c.pageNum" class="chunk-dim t-data">{{ c.pageNum }}</span>
            <span class="chunk-dim t-data">{{ c.tokenCount ?? '—' }} tokens</span>
            <span class="chunk-id-full t-data">{{ c.id.slice(0, 13) }}…</span>
          </div>
          <div class="chunk-body" :class="{ collapsed: !expanded[c.id] }">{{ c.content }}</div>
          <button class="chunk-more" @click="expanded[c.id] = !expanded[c.id]">
            {{ expanded[c.id] ? '收起' : '展开全文' }}
          </button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { listDocuments, getChunks, deleteDocument, uploadDocument, reparseDocument, replaceDocument, etlProgressWsUrl } from '@/api'
import type { KbDoc, KbChunk, EtlProgress } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, Delete, Grid, Loading, RefreshRight } from '@element-plus/icons-vue'

const docs = ref<KbDoc[]>([])
const loading = ref(false)
const uploadOpen = ref(false)
const parseRouteChoice = ref('')

const totalChunks = computed(() =>
  docs.value.reduce((sum, d) => sum + (d.chunkCount ?? 0), 0))

async function refresh() {
  loading.value = true
  try {
    docs.value = await listDocuments()
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

// ── 上传 + WS 实时进度（2.13）──

const liveProgress = reactive<Record<string, { name: string; stage: string; percentage: number }>>({})
const sockets: Record<string, WebSocket> = {}

async function handleUpload(file: File) {
  try {
    const docId = await uploadDocument(file, parseRouteChoice.value || undefined)
    liveProgress[docId] = { name: file.name, stage: 'READING', percentage: 5 }
    subscribe(docId, file.name)
    uploadOpen.value = false
  } catch (e: any) {
    ElMessage.error('上传失败：' + (e.response?.data?.message || e.message))
  }
  return false
}

function subscribe(docId: string, name: string) {
  const ws = new WebSocket(etlProgressWsUrl(docId))
  sockets[docId] = ws
  ws.onmessage = ev => {
    try {
      const p: EtlProgress = JSON.parse(ev.data)
      if (p.docId !== docId) return
      liveProgress[docId] = { name, stage: p.stage, percentage: p.percentage }
      if (p.stage === 'COMPLETED') {
        ElMessage.success(`「${name}」入库完成`)
        finish(docId)
      } else if (p.stage === 'FAILED') {
        ElMessage.error(`「${name}」处理失败`)
        finish(docId)
      }
    } catch { /* 忽略非 JSON 帧 */ }
  }
}

function finish(docId: string) {
  setTimeout(() => {
    sockets[docId]?.close()
    delete sockets[docId]
    delete liveProgress[docId]
    refresh()
  }, 1800)
}

onBeforeUnmount(() => Object.values(sockets).forEach(ws => ws.close()))

// ── 增量重入库（簇⑥ C1）──

/** 仅 SUCCESS/FAILED 可重入库（与后端状态守卫一致） */
const canReindex = (s: string) => s === 'SUCCESS' || s === 'FAILED'

async function confirmReparse(doc: KbDoc) {
  try {
    await ElMessageBox.confirm(
      `以 MinIO 原件重走 ETL 管线（解析 → 切分 → 向量化 → 索引）。` +
      `蓝绿语义：新数据写入后才清理旧 Chunk，重入库期间检索不中断。`,
      `重解析「${doc.name}」`, { type: 'info', confirmButtonText: '重解析', cancelButtonText: '取消' })
  } catch { return }
  try {
    await reparseDocument(doc.id)
    liveProgress[doc.id] = { name: doc.name, stage: 'READING', percentage: 5 }
    subscribe(doc.id, doc.name)
    refresh()
  } catch (e: any) {
    ElMessage.error('重解析发起失败：' + (e.response?.data?.message || e.message))
  }
}

const replaceInput = ref<HTMLInputElement | null>(null)
const replaceTarget = ref<KbDoc | null>(null)

async function confirmReplace(doc: KbDoc) {
  try {
    await ElMessageBox.confirm(
      `选择新版本文件覆盖原件并重走 ETL。文档 ID 与引用保持不变，成功后版本号 +1。`,
      `替换「${doc.name}」`, { type: 'warning', confirmButtonText: '选择文件', cancelButtonText: '取消' })
  } catch { return }
  replaceTarget.value = doc
  replaceInput.value?.click()
}

async function onReplaceFile(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const doc = replaceTarget.value
  if (!file || !doc) return
  try {
    await replaceDocument(doc.id, file)
    liveProgress[doc.id] = { name: file.name, stage: 'READING', percentage: 5 }
    subscribe(doc.id, file.name)
    refresh()
  } catch (e: any) {
    ElMessage.error('替换发起失败：' + (e.response?.data?.message || e.message))
    refresh()
  }
}

// ── Chunk 抽屉（2.15）──

const chunkOpen = ref(false)
const chunks = ref<KbChunk[]>([])
const chunksLoading = ref(false)
const activeDoc = ref<KbDoc | null>(null)
const expanded = reactive<Record<string, boolean>>({})

async function openChunks(doc: KbDoc) {
  activeDoc.value = doc
  chunkOpen.value = true
  chunksLoading.value = true
  chunks.value = []
  try {
    chunks.value = await getChunks(doc.id)
  } finally {
    chunksLoading.value = false
  }
}

// ── 删除 ──

async function confirmDelete(doc: KbDoc) {
  try {
    await ElMessageBox.confirm(
      `删除「${doc.name}」将级联清理 Chunk、向量、ES 索引与 MinIO 原件，不可恢复。`,
      '删除确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return /* 用户取消 */ }
  // 确认后的删除失败必须可见——此前单个 catch 把真实错误与「取消」一并静默吞掉
  try {
    await deleteDocument(doc.id)
    ElMessage.success('已删除')
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e.response?.data?.message || e.message))
  } finally {
    refresh()
  }
}

// ── 展示辅助 ──

const stageLabel = (s: string) =>
  ({ READING: '解析中', TRANSFORMING: '切分中', PERSISTING: '落库中',
     EMBEDDING: '向量化', INDEXING: '索引中', CLEANUP: '清理旧块',
     COMPLETED: '完成', FAILED: '失败' } as Record<string, string>)[s] || s
const isLiveStage = (s: string) => s !== 'COMPLETED' && s !== 'FAILED'

const statusLabel = (s: string) =>
  ({ UPLOADING: '上传中', PARSING: '处理中', REINDEXING: '重入库中',
     SUCCESS: '已入库', FAILED: '失败' } as Record<string, string>)[s] || s
const statusChip = (s: string) =>
  ({ UPLOADING: 'chip-warn', PARSING: 'chip-gold', REINDEXING: 'chip-gold',
     SUCCESS: 'chip-ok', FAILED: 'chip-danger' } as Record<string, string>)[s] || 'chip-mute'
const isLiveDocStatus = (s: string) => s === 'UPLOADING' || s === 'PARSING' || s === 'REINDEXING'

const typeShort = (t: string) =>
  ({ PDF: 'PDF', DOCX: 'Doc', MD: 'MD', TXT: 'Txt', HTML: 'Htm' } as Record<string, string>)[t] || 'File'

const fmtSize = (n: number) => {
  if (!n) return '—'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
const fmtTime = (t: string) => t ? t.replace('T', ' ').slice(0, 16) : '—'
</script>

<style scoped>
.docs-page { height: 100%; overflow-y: auto; padding-bottom: 28px; }

.page-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  padding: 22px 28px 14px; gap: 16px;
}
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }
.head-stats { display: flex; align-items: center; gap: 22px; }
.stat { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat-num { font-size: 22px; font-weight: 700; color: var(--pine-800); line-height: 1; }

.live-list { display: flex; flex-direction: column; gap: 10px; padding: 0 28px; }
.live-card { padding: 12px 16px; border-left: 3px solid var(--gold-500); }
.live-head {
  display: flex; align-items: center; gap: 9px; margin-bottom: 8px;
  font-size: 13px; font-weight: 600; color: var(--ink-2);
}
.live-ico { color: var(--gold-600); animation: spin 1.6s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.live-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.table-wrap { margin: 14px 28px 0; overflow: hidden; }
.doc-cell { display: flex; align-items: center; gap: 11px; }
.doc-ico {
  width: 38px; height: 38px; border-radius: 9px; flex-shrink: 0;
  display: grid; place-items: center;
  font-family: var(--font-data); font-size: 10.5px; font-weight: 700;
  background: var(--pine-50); color: var(--pine-700); border: 1px solid var(--pine-100);
}
.doc-ico.ico-pdf { background: #FAECEB; color: #B0504C; border-color: #F0D2D0; }
.doc-ico.ico-docx { background: #E8F0FA; color: #3D6BA8; border-color: #CFE0F2; }
.doc-ico.ico-md { background: #FAF3E4; color: #A87C2A; border-color: #EEDFBC; }
.doc-names { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.doc-name { font-weight: 600; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.doc-meta { font-size: 11px; color: var(--ink-3); }
.doc-dim { color: var(--ink-3); }
.chunk-num { font-weight: 600; color: var(--pine-800); }
.mini-pulse {
  width: 6px; height: 6px; border-radius: 50%; background: currentColor;
  animation: caret-blink 1.2s ease infinite;
}

.route-row { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.route-label { font-size: 13px; color: var(--ink-2); font-weight: 600; flex-shrink: 0; }
.upload-zone :deep(.el-upload-dragger) { padding: 34px 20px; }
.upload-cloud { font-size: 46px; color: var(--pine-600); }
.upload-hint { margin-top: 10px; font-size: 14px; color: var(--ink-2); }
.upload-hint em { color: var(--pine-700); font-style: normal; font-weight: 600; }
.upload-formats { margin-top: 5px; font-size: 12px; color: var(--ink-3); }
.upload-note { margin-top: 12px; font-size: 12px; color: var(--ink-3); line-height: 1.7; }

.chunk-meta-row { display: flex; gap: 8px; margin-bottom: 14px; }
.chunk-list { display: flex; flex-direction: column; gap: 12px; }
.chunk-card { padding: 13px 16px; }
.chunk-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.chunk-idx { font-weight: 700; color: var(--pine-800); font-size: 13px; }
.chunk-dim { font-size: 11px; color: var(--ink-3); }
.chunk-id-full { margin-left: auto; font-size: 10.5px; color: var(--ink-3); }
.chunk-body {
  font-size: 12.5px; color: var(--ink-2); line-height: 1.75; white-space: pre-wrap;
  word-break: break-word;
}
.chunk-body.collapsed {
  display: -webkit-box; -webkit-line-clamp: 5; -webkit-box-orient: vertical; overflow: hidden;
}
.chunk-more {
  margin-top: 7px; border: none; background: none; cursor: pointer; padding: 0;
  font-size: 12px; font-weight: 600; color: var(--pine-700); font-family: var(--font-body);
}
.chunk-more:hover { color: var(--pine-600); }

.slide-fade-enter-active { transition: all .35s var(--ease); }
.slide-fade-leave-active { transition: all .25s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
