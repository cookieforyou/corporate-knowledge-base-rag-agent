<template>
  <div class="tool-card panel">
    <div class="tool-head">
      <el-icon class="tool-ico"><Operation /></el-icon>
      <span class="tool-name">{{ call.toolName }}</span>
      <span class="chip" :class="statusChipClass">{{ statusLabel }}</span>
    </div>
    <p v-if="call.summary" class="tool-summary">{{ call.summary }}</p>

    <!-- HITL 审批区（PENDING_APPROVAL 且未失效/未本地拒绝） -->
    <div v-if="pending" class="tool-actions">
      <el-button type="primary" size="small" round :loading="busy" @click="approve">
        同意执行
      </el-button>
      <el-button size="small" round :disabled="busy" @click="reject">拒绝</el-button>
      <span class="tool-hint">写操作需人工确认 · 审批单 10 分钟内有效</span>
    </div>
    <p v-else-if="call.expired" class="tool-expired">
      审批单已失效或无权确认（一次性消费 / 逾 10 分钟 TTL），请重新发起请求
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { approveToolCall, type ToolCallInfo } from '@/api'
import { ElMessage } from 'element-plus'
import { Operation } from '@element-plus/icons-vue'

const props = defineProps<{ call: ToolCallInfo }>()
const emit = defineEmits<{ (e: 'confirmed', approvalId: string): void }>()

const busy = ref(false)

const pending = computed(() =>
  props.call.status === 'PENDING_APPROVAL' && !props.call.expired)

const statusLabel = computed(() => {
  if (props.call.expired) return '已失效'
  return ({
    PENDING_APPROVAL: '待审批',
    EXECUTED: '已执行',
    REJECTED: '已拒绝'
  } as Record<string, string>)[props.call.status] || props.call.status
})

const statusChipClass = computed(() => {
  if (props.call.expired) return 'chip-mute'
  return ({
    PENDING_APPROVAL: 'chip-gold',
    EXECUTED: 'chip-pine',
    REJECTED: 'chip-mute'
  } as Record<string, string>)[props.call.status] || 'chip-mute'
})

async function approve() {
  const id = props.call.approvalId
  if (!id) return
  busy.value = true
  try {
    const approved = await approveToolCall(id)
    if (approved) {
      emit('confirmed', id)   // 父组件携带 approvedToolCallId 发起确认轮
    } else {
      props.call.expired = true   // 跨租户/越权/失效：不暴露细节
    }
  } catch (e: any) {
    ElMessage.error('审批请求失败：' + (e.response?.data?.message || e.message))
  } finally {
    busy.value = false
  }
}

/** 拒绝：后端无拒绝端点，本地置 REJECTED，审批单任 TTL 过期（11.2.1） */
function reject() {
  props.call.status = 'REJECTED'
}
</script>

<style scoped>
.tool-card { padding: 12px 16px; border-left: 3px solid var(--gold-500); }
.tool-head { display: flex; align-items: center; gap: 8px; }
.tool-ico { color: var(--gold-600); }
.tool-name { font-size: 13px; font-weight: 600; color: var(--ink-2); font-family: var(--font-data); }
.tool-summary { margin: 8px 0 0; font-size: 13px; line-height: 1.7; color: var(--ink-2); }
.tool-actions { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.tool-hint { font-size: 11.5px; color: var(--ink-3); margin-left: auto; }
.tool-expired { margin: 10px 0 0; font-size: 12px; color: var(--ink-3); }
</style>
