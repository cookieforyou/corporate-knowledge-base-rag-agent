<template>
  <el-dialog
    :model-value="!!target"
    :title="target?.fileName || '原文'"
    width="640px"
    :close-on-click-modal="true"
    @update:model-value="(v: boolean) => !v && emit('close')"
    @close="emit('close')">
    <div v-loading="loading" class="source-body">
      <template v-if="chunk">
        <div class="source-meta">
          <span class="chip" :class="chunk.chunkType === 'TABLE' ? 'chip-gold' : 'chip-pine'">
            {{ chunk.chunkType }}
          </span>
          <span v-if="chunk.pageNum" class="meta-dim t-data">第 {{ chunk.pageNum }} 页</span>
          <span class="meta-dim t-data">#{{ chunk.chunkIndex }}</span>
          <span class="meta-dim t-data">{{ chunk.tokenCount ?? '—' }} tokens</span>
        </div>
        <!-- 纯文本形态展示（与 Chunks 观测台一致）：DocMind HTML 表格不渲染，防 XSS -->
        <pre class="source-content">{{ chunk.content }}</pre>
      </template>
      <el-empty v-else-if="!loading" :description="errorMsg || '未找到该证据块'" :image-size="72" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { getChunks, type KbChunk } from '@/api'

/** 溯源目标：chunkId + docId（TRACE 投影 3.15 起携带 docId） */
export interface SourceTarget {
  chunkId: string
  docId: string
  fileName?: string | null
  pageNum?: number | null
}

const props = defineProps<{ target: SourceTarget | null }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const loading = ref(false)
const chunk = ref<KbChunk | null>(null)
const errorMsg = ref('')

/** 文档 chunk 全量缓存（页面级）：同一文档的多条证据只拉一次 */
const docChunkCache = new Map<string, KbChunk[]>()

watch(() => props.target, async t => {
  chunk.value = null
  errorMsg.value = ''
  if (!t) return
  loading.value = true
  try {
    let list = docChunkCache.get(t.docId)
    if (!list) {
      list = await getChunks(t.docId)
      docChunkCache.set(t.docId, list)
    }
    chunk.value = list.find(c => c.id === t.chunkId) || null
  } catch (e: any) {
    errorMsg.value = '原文加载失败：' + (e.response?.data?.message || e.message)
  } finally {
    loading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.source-body { min-height: 120px; max-height: 60vh; overflow-y: auto; }
.source-meta { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.meta-dim { font-size: 12px; color: var(--ink-3); }
.source-content {
  margin: 0; padding: 14px 16px;
  background: var(--surface-2); border: 1px solid var(--line);
  border-radius: var(--radius-md, 8px);
  font-size: 13px; line-height: 1.8; color: var(--ink-2);
  white-space: pre-wrap; word-break: break-word;
  font-family: var(--font-body);
}
</style>
