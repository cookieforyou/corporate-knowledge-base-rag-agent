<template>
  <!-- 历史会话栏（3.15 补齐）：可收起；选中加载历史消息恢复溯源；流式中禁切换 -->
  <aside v-if="!collapsed" class="session-panel">
    <div class="sp-head">
      <span class="sp-title t-label">历史会话</span>
      <button class="sp-icon-btn" title="收起" @click="collapse">
        <el-icon><DArrowLeft /></el-icon>
      </button>
    </div>
    <div ref="listEl" class="sp-list" @scroll="onScroll">
      <div v-for="s in sessions" :key="s.id" class="sp-item panel"
        :class="{ active: s.id === activeId, disabled: disabled }"
        @click="select(s.id)">
        <div class="sp-item-title">{{ s.title || '未命名会话' }}</div>
        <div class="sp-item-meta">
          <span class="sp-time t-data">{{ relTime(s.updatedAt) }}</span>
          <el-popconfirm title="删除该会话？消息将一并清除且不可恢复" width="220"
            confirm-button-text="删除" cancel-button-text="取消" @confirm="remove(s)">
            <template #reference>
              <button class="sp-del" :disabled="disabled" title="删除会话" @click.stop>
                <el-icon><Delete /></el-icon>
              </button>
            </template>
          </el-popconfirm>
        </div>
      </div>
      <div v-if="sessions.length === 0 && !loading" class="sp-empty">暂无历史会话</div>
      <div v-if="loading" class="sp-empty">加载中…</div>
      <div v-else-if="hasMore && sessions.length > 0" class="sp-empty sp-more-hint">下拉加载更多</div>
    </div>
  </aside>
  <button v-else class="session-rail" title="展开历史会话" @click="expand">
    <el-icon><ChatDotSquare /></el-icon>
  </button>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listSessions, deleteSession, type SessionSummary } from '@/api'
import { ElMessage } from 'element-plus'
import { DArrowLeft, ChatDotSquare, Delete } from '@element-plus/icons-vue'

const props = defineProps<{ activeId: string; disabled?: boolean }>()
const emit = defineEmits<{ select: [id: string]; deleted: [id: string] }>()

const PAGE_SIZE = 50

const sessions = ref<SessionSummary[]>([])
const loading = ref(false)
const collapsed = ref(false)
const page = ref(0)
const hasMore = ref(true)
const listEl = ref<HTMLElement>()

async function load(p: number, append: boolean) {
  if (loading.value) return
  loading.value = true
  try {
    const data = await listSessions(p, PAGE_SIZE)
    sessions.value = append ? [...sessions.value, ...data] : data
    page.value = p
    hasMore.value = data.length === PAGE_SIZE
  } catch (e: any) {
    ElMessage.error('会话列表加载失败：' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

/** 重新拉取第一页（轮次完成后由 Chat.vue 调用，刷新标题/排序） */
function refresh() {
  load(0, false)
}

function select(id: string) {
  if (props.disabled || id === props.activeId) return
  emit('select', id)
}

async function remove(s: SessionSummary) {
  try {
    await deleteSession(s.id)
    sessions.value = sessions.value.filter(x => x.id !== s.id)
    emit('deleted', s.id)
    ElMessage.success('会话已删除')
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e.response?.data?.message || e.message))
  }
}

function onScroll() {
  const el = listEl.value
  if (!el || loading.value || !hasMore.value) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 40) {
    load(page.value + 1, true)
  }
}

function collapse() {
  collapsed.value = true
}

function expand() {
  collapsed.value = false
  refresh()
}

/** 相对时间：x 分钟前 / x 小时前 / x 天前，超 30 天回退日期 */
function relTime(iso: string): string {
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return ''
  const diffMin = Math.floor((Date.now() - t) / 60000)
  if (diffMin < 1) return '刚刚'
  if (diffMin < 60) return `${diffMin} 分钟前`
  const hr = Math.floor(diffMin / 60)
  if (hr < 24) return `${hr} 小时前`
  const day = Math.floor(hr / 24)
  if (day < 30) return `${day} 天前`
  return iso.slice(0, 10)
}

defineExpose({ refresh })
onMounted(refresh)
</script>

<style scoped>
.session-panel {
  width: 224px; flex-shrink: 0; display: flex; flex-direction: column;
  border-right: 1px solid var(--line); background: var(--surface-2);
  height: 100%; min-height: 0;
}
.sp-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 12px 8px 16px;
}
.sp-title { font-size: 12px; color: var(--ink-3); letter-spacing: .06em; }
.sp-icon-btn {
  border: none; background: none; cursor: pointer; color: var(--ink-3);
  display: grid; place-items: center; width: 24px; height: 24px; border-radius: 6px;
  transition: background .2s, color .2s;
}
.sp-icon-btn:hover { background: var(--pine-50); color: var(--pine-700); }

.sp-list { flex: 1; overflow-y: auto; padding: 4px 10px 12px; display: flex; flex-direction: column; gap: 6px; }

.sp-item {
  padding: 9px 10px; cursor: pointer; border: 1px solid transparent;
  transition: border-color .2s var(--ease), background .2s var(--ease);
}
.sp-item:hover { border-color: var(--line-strong); }
.sp-item.active { border-color: var(--pine-600); background: var(--pine-50); }
.sp-item.disabled { cursor: not-allowed; opacity: .55; }
.sp-item-title {
  font-size: 13px; font-weight: 600; color: var(--ink-2); line-height: 1.45;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.sp-item.active .sp-item-title { color: var(--pine-900); }
.sp-item-meta { display: flex; align-items: center; justify-content: space-between; margin-top: 3px; }
.sp-time { font-size: 11px; color: var(--ink-3); }
/* 隐藏用 visibility/opacity 而非 display:none：popconfirm 弹出后鼠标移入确认框
   会脱离卡片 hover，display:none 会使引用元素失去布局盒，popper 定位回退到页面左上角 */
.sp-del {
  border: none; background: none; cursor: pointer; color: var(--ink-3);
  width: 20px; height: 20px; border-radius: 5px; display: grid; place-items: center;
  visibility: hidden; opacity: 0;
  transition: background .2s, color .2s, opacity .2s;
}
.sp-item:hover .sp-del { visibility: visible; opacity: 1; }
.sp-del:hover { background: #FBEAEA; color: #C0392B; }
.sp-del:disabled { visibility: hidden; opacity: 0; }

.sp-empty { text-align: center; color: var(--ink-3); font-size: 12px; padding: 18px 0; }
.sp-more-hint { padding: 6px 0; }

/* 收起态：窄轨展开按钮 */
.session-rail {
  flex-shrink: 0; width: 26px; border: none; border-right: 1px solid var(--line);
  background: var(--surface-2); cursor: pointer; color: var(--ink-3);
  display: flex; justify-content: center; padding-top: 16px;
  transition: background .2s, color .2s;
}
.session-rail:hover { background: var(--pine-50); color: var(--pine-700); }
</style>
