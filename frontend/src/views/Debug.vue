<template>
  <div class="debug-page">
    <header class="page-head reveal">
      <div>
        <h1 class="t-display page-title">检索调试台</h1>
        <p class="page-desc">直调检索链路（不经 LLM）——改写 → 多路召回 → RRF 融合 → 重排，全维度得分透视</p>
      </div>
    </header>

    <!-- ══ 查询台 ══ -->
    <div class="console panel reveal" style="--d:.05s">
      <el-input v-model="query" size="large" placeholder="输入查询，透视每个候选 Chunk 的全链路得分"
        clearable @keydown.enter="run">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" size="large" :loading="running" @click="run">执行检索</el-button>
    </div>

    <!-- ══ 结果概览 ══ -->
    <template v-if="result">
      <div class="overview reveal" style="--d:.08s">
        <!-- 时延面板 -->
        <div class="lat-panel panel">
          <div class="t-label lat-title">链路时延</div>
          <div class="lat-grid">
            <div class="lat-tile" v-for="l in latencies" :key="l.key">
              <span class="lat-num t-data" :style="{ color: l.color }">{{ l.value }}</span>
              <span class="lat-key">{{ l.label }}</span>
            </div>
          </div>
          <div class="degrade-row">
            <span class="chip" v-for="(v, k) in result.degradation" :key="k"
              :class="v === 'OK' ? 'chip-ok' : 'chip-danger'">
              {{ routeLabel(k) }} · {{ v }}
            </span>
          </div>
        </div>

        <!-- 改写对照 -->
        <div class="rewrite-panel panel">
          <div class="t-label">查询改写（RewriteQueryTransformer）</div>
          <div class="rewrite-pair">
            <div class="rewrite-item">
              <span class="chip chip-mute">原始</span>
              <p>{{ result.query }}</p>
            </div>
            <el-icon class="rewrite-arrow"><Right /></el-icon>
            <div class="rewrite-item">
              <span class="chip chip-gold">改写</span>
              <p :class="{ changed: result.rewrittenQuery !== result.query }">
                {{ result.rewrittenQuery }}</p>
            </div>
          </div>
        </div>
      </div>

      <!-- ══ 候选列表 ══ -->
      <div class="cands">
        <div v-for="(c, i) in result.candidates" :key="c.chunkId"
          class="cand panel panel-lift reveal" :style="{ '--d': `${Math.min(i * 0.05, 0.45)}s` }">
          <div class="cand-head">
            <span class="cand-rank t-data" :class="{ final: c.finalRank }">
              {{ c.finalRank ? `TOP ${c.finalRank}` : '未入选' }}
            </span>
            <span class="t-data cand-id">{{ c.chunkId.slice(0, 13) }}…</span>
            <span v-if="c.fileName" class="cand-file">{{ c.fileName }}</span>
            <span v-if="c.chunkType" class="chip chip-mute">{{ c.chunkType }}</span>
            <span v-if="c.pageNum" class="cand-dim t-data">{{ c.pageNum }}</span>
          </div>

          <!-- 全维度得分条 -->
          <div class="score-grid">
            <div class="score-row" v-for="s in scoreDims(c)" :key="s.key">
              <span class="score-key">
                <i class="score-dot" :style="{ background: s.color }" />{{ s.label }}
              </span>
              <div class="score-track">
                <div class="score-bar" :style="{
                  width: barWidth(s.value, s.max) + '%', background: s.color,
                  transitionDelay: `${i * 40}ms` }" />
              </div>
              <span class="score-val t-data">{{ fmtVal(s.value) }}</span>
              <span v-if="s.rank != null" class="score-rank t-data">#{{ s.rank }}</span>
            </div>
          </div>

          <div class="cand-body" :class="{ collapsed: !expanded[c.chunkId] }">{{ c.content }}</div>
          <button class="cand-more" @click="expanded[c.chunkId] = !expanded[c.chunkId]">
            {{ expanded[c.chunkId] ? '收起' : '展开全文' }}
          </button>
        </div>
      </div>
    </template>

    <!-- 空态 -->
    <div v-else-if="!running" class="empty-tip reveal" style="--d:.1s">
      <el-icon :size="38"><DataAnalysis /></el-icon>
      <p>执行一次检索，查看 向量分 / BM25 分 / 图谱分 / 融合分 / 重排分 的完整对比</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { retrievalSearch } from '@/api'
import type { RetrievalDebugResult, RetrievalCandidate } from '@/api'
import { ElMessage } from 'element-plus'
import { Search, Right, DataAnalysis } from '@element-plus/icons-vue'

const query = ref('')
const running = ref(false)
const result = ref<RetrievalDebugResult | null>(null)
const expanded = reactive<Record<string, boolean>>({})

async function run() {
  const q = query.value.trim()
  if (!q || running.value) return
  running.value = true
  try {
    result.value = await retrievalSearch(q)
  } catch (e: any) {
    ElMessage.error('检索失败：' + (e.response?.data?.message || e.message))
  } finally {
    running.value = false
  }
}

const latencies = computed(() => {
  if (!result.value) return []
  const l = result.value.latencyMs
  return [
    { key: 'rewrite', label: '改写', value: l.rewrite, color: 'var(--gold-600)' },
    { key: 'retrieval', label: '多路召回', value: l.retrieval, color: 'var(--c-vector)' },
    { key: 'rerank', label: '重排', value: l.rerank, color: 'var(--c-rerank)' },
    { key: 'total', label: '全链路', value: l.total, color: 'var(--pine-800)' }
  ]
})

/** 每维度的最大值（条带按结果集内归一化） */
const maxes = computed(() => {
  const cs = result.value?.candidates ?? []
  const max = (pick: (c: RetrievalCandidate) => number | undefined | null) =>
    Math.max(...cs.map(c => Math.abs(pick(c) ?? 0)), 1e-9)
  return {
    vector: max(c => c.vectorScore),
    bm25: max(c => c.bm25Score),
    graph: max(c => c.graphScore),
    fusion: max(c => c.fusionScore),
    rerank: max(c => c.rerankScore)
  }
})

function scoreDims(c: RetrievalCandidate) {
  const m = maxes.value
  // 图路维度（簇④）：仅该候选命中图路时呈现（关闭态/未命中自然缺位，零空行）
  type Dim = { key: string; label: string; value: number | undefined | null; rank: number | null | undefined; max: number; color: string }
  const dims: Dim[] = [
    { key: 'vector', label: '向量相似度', value: c.vectorScore, rank: c.vectorRank, max: m.vector, color: 'var(--c-vector)' },
    { key: 'bm25', label: 'BM25', value: c.bm25Score, rank: c.bm25Rank, max: m.bm25, color: 'var(--c-bm25)' }
  ]
  if (c.graphScore != null || c.graphRank != null) {
    dims.push({ key: 'graph', label: c.graphEntityHits ? `图谱·${c.graphEntityHits}` : '图谱', value: c.graphScore, rank: c.graphRank, max: m.graph, color: 'var(--c-graph, var(--c-bm25))' })
  }
  dims.push(
    { key: 'fusion', label: 'RRF 融合', value: c.fusionScore, rank: null, max: m.fusion, color: 'var(--c-fusion)' },
    { key: 'rerank', label: '重排分', value: c.rerankScore, rank: c.rerankRank, max: m.rerank, color: 'var(--c-rerank)' }
  )
  return dims
}

/** 降级芯片路由标签：键映射与 Chat 溯源同族（簇④ 三路扩展，未知键原样回显） */
const routeLabel = (k: string) =>
  ({ vector: '向量路', bm25: 'BM25 路', graph: '图谱路' } as Record<string, string>)[k] || k

const barWidth = (v: number | undefined | null, max: number) =>
  v == null ? 0 : Math.max((Math.abs(v) / max) * 100, 2)

const fmtVal = (v: number | undefined | null) =>
  v == null ? '—' : Math.abs(v) >= 10 ? v.toFixed(2) : Number(v).toFixed(4)
</script>

<style scoped>
.debug-page { height: 100%; overflow-y: auto; padding-bottom: 28px; }

.page-head { padding: 22px 28px 14px; }
.page-title { margin: 0; font-size: 24px; color: var(--pine-900); }
.page-desc { margin: 5px 0 0; color: var(--ink-3); font-size: 13px; }

.console {
  display: flex; gap: 12px; margin: 0 28px; padding: 16px;
}
.console :deep(.el-input__wrapper) { box-shadow: none; background: var(--surface-2); }

.overview {
  display: grid; grid-template-columns: 340px 1fr; gap: 14px;
  margin: 14px 28px 0;
}
.lat-panel, .rewrite-panel { padding: 15px 17px; }
.lat-title { margin-bottom: 10px; }
.lat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.lat-tile {
  background: var(--surface-2); border: 1px solid var(--line); border-radius: 9px;
  padding: 9px 12px; display: flex; flex-direction: column; gap: 2px;
  transition: transform .2s var(--ease);
}
.lat-tile:hover { transform: translateY(-1px); }
.lat-num { font-size: 20px; font-weight: 700; line-height: 1.1; }
.lat-num::after { content: ' ms'; font-size: 11px; color: var(--ink-3); font-weight: 500; }
.lat-key { font-size: 11px; color: var(--ink-3); }
.degrade-row { display: flex; gap: 8px; margin-top: 12px; }

.rewrite-panel .t-label { margin-bottom: 10px; }
.rewrite-pair { display: flex; align-items: center; gap: 12px; }
.rewrite-item {
  flex: 1; background: var(--surface-2); border: 1px solid var(--line);
  border-radius: 9px; padding: 10px 13px;
}
.rewrite-item p { margin: 7px 0 0; font-size: 13px; line-height: 1.65; color: var(--ink-2); }
.rewrite-item p.changed { color: var(--pine-800); font-weight: 500; }
.rewrite-arrow { color: var(--ink-3); flex-shrink: 0; }

.cands { display: flex; flex-direction: column; gap: 12px; margin: 14px 28px 0; }
.cand { padding: 15px 18px; }
.cand-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.cand-rank {
  padding: 3px 10px; border-radius: 6px; font-size: 11.5px; font-weight: 700;
  background: #EEF1F0; color: var(--ink-3);
}
.cand-rank.final { background: var(--pine-800); color: var(--gold-300); }
.cand-id { font-size: 11.5px; color: var(--ink-3); }
.cand-file { font-size: 12px; color: var(--ink-2); }
.cand-dim { font-size: 11px; color: var(--ink-3); }

.score-grid { display: flex; flex-direction: column; gap: 7px; margin-bottom: 12px; }
.score-row { display: grid; grid-template-columns: 110px 1fr 74px 34px; align-items: center; gap: 10px; }
.score-key { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--ink-2); }
.score-dot { width: 8px; height: 8px; border-radius: 3px; flex-shrink: 0; }
.score-track {
  height: 10px; border-radius: 5px; background: var(--surface-2);
  border: 1px solid var(--line); overflow: hidden;
}
.score-bar {
  height: 100%; border-radius: 5px; min-width: 0;
  transition: width .7s var(--ease);
}
.score-val { font-size: 11.5px; color: var(--ink-2); text-align: right; }
.score-rank { font-size: 11px; color: var(--ink-3); text-align: right; }

.cand-body {
  font-size: 12.5px; color: var(--ink-2); line-height: 1.75;
  white-space: pre-wrap; word-break: break-word;
  border-top: 1px dashed var(--line); padding-top: 10px;
}
.cand-body.collapsed {
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
}
.cand-more {
  margin-top: 7px; border: none; background: none; cursor: pointer; padding: 0;
  font-size: 12px; font-weight: 600; color: var(--pine-700); font-family: var(--font-body);
}

.empty-tip {
  margin: 9vh auto 0; text-align: center; color: var(--ink-3);
}
.empty-tip p { margin-top: 12px; font-size: 13.5px; }
</style>
