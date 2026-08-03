<template>
  <div class="chunks-page">
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">Chunk 观测台</h1>
        <p class="page-desc">检视切分质量——每个 Chunk 的内容、类型、token 规模与元数据</p>
      </div>
      <div class="doc-picker">
        <el-select v-model="docId" placeholder="选择文档" size="large" filterable
          style="width: 300px" @change="loadChunks">
          <el-option v-for="d in docs" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </div>
    </header>

    <template v-if="docId">
      <!-- 统计条 -->
      <div class="stat-strip reveal" style="--d:.05s">
        <div class="stat-item panel">
          <span class="stat-num t-data">{{ chunks.length }}</span>
          <span class="t-label">Chunks</span>
        </div>
        <div class="stat-item panel">
          <span class="stat-num t-data">{{ totalTokens }}</span>
          <span class="t-label">总 Tokens（估算）</span>
        </div>
        <div class="stat-item panel">
          <span class="stat-num t-data">{{ avgTokens }}</span>
          <span class="t-label">平均 Tokens</span>
        </div>
        <div class="stat-item panel">
          <span class="stat-num t-data">{{ tableChunks }}</span>
          <span class="t-label">表格 Chunks</span>
        </div>
      </div>

      <!-- token 分布图（纯 CSS 条形） -->
      <div v-if="chunks.length" class="dist panel reveal" style="--d:.08s">
        <div class="t-label">Token 规模分布</div>
        <div class="dist-bars">
          <el-tooltip v-for="c in chunks" :key="c.id"
            :content="`#${c.chunkIndex} · ${c.tokenCount ?? '—'} tokens`" placement="top">
            <div class="dist-bar" :style="{ height: barHeight(c) + 'px' }"
              :class="{ table: c.chunkType === 'TABLE' }" />
          </el-tooltip>
        </div>
      </div>

      <!-- Chunk 列表 -->
      <div v-loading="loading" class="chunk-list">
        <div v-for="(c, i) in chunks" :key="c.id" class="chunk-card panel panel-lift reveal"
          :style="{ '--d': `${Math.min(i * 0.035, 0.4)}s` }">
          <div class="chunk-head">
            <span class="chunk-idx t-data">#{{ c.chunkIndex }}</span>
            <span class="chip" :class="c.chunkType === 'TABLE' ? 'chip-gold' : 'chip-pine'">
              {{ c.chunkType }}
            </span>
            <span v-if="c.pageNum" class="chunk-dim t-data">{{ c.pageNum }}</span>
            <span class="chunk-dim t-data">{{ c.tokenCount ?? '—' }} tokens</span>
            <span class="chunk-id t-data">{{ c.id }}</span>
          </div>
          <div class="chunk-body" :class="{ collapsed: !expanded[c.id] }">{{ c.content }}</div>
          <button class="chunk-more" @click="expanded[c.id] = !expanded[c.id]">
            {{ expanded[c.id] ? '收起' : '展开全文' }}
          </button>
        </div>
      </div>
    </template>

    <div v-else class="empty-tip reveal" style="--d:.08s">
      <el-icon :size="38"><Grid /></el-icon>
      <p>选择一份已入库的文档，观测其 Chunk 切分结果</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { listDocuments, getChunks } from '@/api'
import type { KbDoc, KbChunk } from '@/api'
import { Grid } from '@element-plus/icons-vue'

const route = useRoute()
const docs = ref<KbDoc[]>([])
const docId = ref('')
const chunks = ref<KbChunk[]>([])
const loading = ref(false)
const expanded = reactive<Record<string, boolean>>({})

onMounted(async () => {
  docs.value = (await listDocuments()).filter(d => d.status === 'SUCCESS')
  const preset = route.query.doc as string
  if (preset && docs.value.some(d => d.id === preset)) {
    docId.value = preset
    loadChunks()
  }
})

async function loadChunks() {
  if (!docId.value) return
  loading.value = true
  chunks.value = []
  try {
    chunks.value = await getChunks(docId.value)
  } finally {
    loading.value = false
  }
}

const totalTokens = computed(() =>
  chunks.value.reduce((s, c) => s + (c.tokenCount ?? 0), 0))
const avgTokens = computed(() =>
  chunks.value.length ? Math.round(totalTokens.value / chunks.value.length) : 0)
const tableChunks = computed(() =>
  chunks.value.filter(c => c.chunkType === 'TABLE').length)

const maxToken = computed(() =>
  Math.max(...chunks.value.map(c => c.tokenCount ?? 0), 1))

const barHeight = (c: KbChunk) =>
  Math.max(((c.tokenCount ?? 0) / maxToken.value) * 64, 6)
</script>

<style scoped>
.chunks-page { height: 100%; overflow-y: auto; padding-bottom: 28px; }

.page-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  padding: 22px 28px 14px; gap: 16px;
}
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }

.stat-strip {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
  margin: 0 28px;
}
.stat-item {
  padding: 13px 16px; display: flex; flex-direction: column; gap: 3px;
  border-top: 3px solid var(--pine-600);
}
.stat-item:nth-child(2) { border-top-color: var(--gold-500); }
.stat-item:nth-child(3) { border-top-color: var(--c-vector); }
.stat-item:nth-child(4) { border-top-color: var(--c-rerank); }
.stat-num { font-size: 24px; font-weight: 700; color: var(--pine-900); line-height: 1.1; }

.dist { margin: 14px 28px 0; padding: 14px 17px; }
.dist-bars {
  display: flex; align-items: flex-end; gap: 3px;
  height: 70px; margin-top: 12px;
}
.dist-bar {
  flex: 1; max-width: 14px; border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, var(--pine-600), var(--pine-800));
  transition: height .5s var(--ease), opacity .2s;
  cursor: default;
}
.dist-bar.table { background: linear-gradient(180deg, var(--gold-500), var(--gold-600)); }
.dist-bar:hover { opacity: .75; }

.chunk-list { display: flex; flex-direction: column; gap: 12px; margin: 14px 28px 0; }
.chunk-card { padding: 14px 17px; }
.chunk-head { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; margin-bottom: 9px; }
.chunk-idx { font-weight: 700; color: var(--pine-800); font-size: 13.5px; }
.chunk-dim { font-size: 11px; color: var(--ink-3); }
.chunk-id { margin-left: auto; font-size: 10.5px; color: var(--ink-3); }
.chunk-body {
  font-size: 12.5px; color: var(--ink-2); line-height: 1.75;
  white-space: pre-wrap; word-break: break-word;
  border-top: 1px dashed var(--line); padding-top: 10px;
}
.chunk-body.collapsed {
  display: -webkit-box; -webkit-line-clamp: 5; -webkit-box-orient: vertical; overflow: hidden;
}
.chunk-more {
  margin-top: 7px; border: none; background: none; cursor: pointer; padding: 0;
  font-size: 12px; font-weight: 600; color: var(--pine-700); font-family: var(--font-body);
}

.empty-tip { margin: 9vh auto 0; text-align: center; color: var(--ink-3); }
.empty-tip p { margin-top: 12px; font-size: 13.5px; }
</style>
