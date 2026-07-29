<template>
  <div class="chat-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="logo">📚 KB RAG Agent</div>
      <el-menu default-active="chat" router>
        <el-menu-item index="/">
          <el-icon><ChatDotRound /></el-icon> 对话
        </el-menu-item>
      </el-menu>
      <div class="sidebar-footer">
        <el-button text @click="auth.logout()">退出登录</el-button>
      </div>
    </aside>

    <!-- 主区域 -->
    <main class="main">
      <header class="header">
        <span>AI 知识库助手</span>
        <el-tag size="small">DeepSeek V4</el-tag>
      </header>

      <!-- 消息列表 -->
      <div ref="msgList" class="messages">
        <div v-for="(msg, i) in messages" :key="i" :class="['msg', msg.role]">
          <div class="msg-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
          <div class="msg-content">{{ msg.content }}</div>
        </div>
        <div v-if="streaming" class="msg assistant">
          <div class="msg-avatar">🤖</div>
          <div class="msg-content">{{ streamText }}<span class="cursor">|</span></div>
        </div>
      </div>

      <!-- 输入区 -->
      <footer class="input-area">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          placeholder="输入问题，按 Enter 发送，Shift+Enter 换行"
          :disabled="streaming"
          @keydown.enter.exact="send"
        />
        <el-button type="primary" :disabled="!input.trim() || streaming" @click="send"
          >发送</el-button
        >
        <el-upload
          :show-file-list="false"
          :before-upload="handleUpload"
          accept=".pdf,.docx,.md,.txt,.html"
        >
          <el-button :disabled="streaming" text>📎 上传文档</el-button>
        </el-upload>
      </footer>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { chatStreamUrl, uploadDocument } from '@/api'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const messages = ref<Message[]>([])
const input = ref('')
const streamText = ref('')
const streaming = ref(false)
const msgList = ref<HTMLElement>()

function scrollToBottom() {
  nextTick(() => {
    if (msgList.value) msgList.value.scrollTop = msgList.value.scrollHeight
  })
}

async function send() {
  const query = input.value.trim()
  if (!query || streaming.value) return

  messages.value.push({ role: 'user', content: query })
  input.value = ''
  scrollToBottom()

  streaming.value = true
  streamText.value = ''

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

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (data === '[DONE]') continue
        try {
          const json = JSON.parse(data)
          if (json.token) streamText.value += json.token
        } catch {}
      }
    }
    messages.value.push({ role: 'assistant', content: streamText.value })
  } catch (e: any) {
    messages.value.push({ role: 'assistant', content: '错误: ' + e.message })
  } finally {
    streaming.value = false
    streamText.value = ''
    scrollToBottom()
  }
}

async function handleUpload(file: File) {
  try {
    await uploadDocument(file)
    ElMessage.success(`文档 "${file.name}" 上传成功，正在处理中...`)
  } catch (e: any) {
    ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message))
  }
  return false // 阻止默认上传行为
}
</script>

<style scoped>
.chat-layout { display: flex; height: 100vh; }
.sidebar {
  width: 220px; background: #1d1e1f; color: #fff;
  display: flex; flex-direction: column;
}
.logo { padding: 20px 16px 12px; font-size: 16px; font-weight: 600; }
.sidebar :deep(.el-menu) { border-right: none; }
.sidebar-footer { margin-top: auto; padding: 12px 16px; }
.main { flex: 1; display: flex; flex-direction: column; }
.header {
  height: 52px; padding: 0 20px; display: flex; align-items: center;
  justify-content: space-between; border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
}
.messages {
  flex: 1; overflow-y: auto; padding: 20px;
}
.msg { display: flex; gap: 12px; margin-bottom: 20px; }
.msg.user { flex-direction: row-reverse; }
.msg.user .msg-content { background: #ecf5ff; }
.msg.assistant .msg-content { background: #f5f7fa; }
.msg-avatar { width: 36px; height: 36px; display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; }
.msg-content { max-width: 70%; padding: 12px 16px; border-radius: 12px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.cursor { animation: blink 1s infinite; color: #409eff; }
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
.input-area {
  padding: 16px 20px; border-top: 1px solid #e4e7ed;
  display: flex; gap: 10px; align-items: flex-end;
}
.input-area :deep(.el-textarea__inner) { resize: none; }
</style>
