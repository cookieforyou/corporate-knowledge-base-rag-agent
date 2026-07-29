<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>企业知识库 RAG Agent 工作台</h2>
      <p class="subtitle">统一身份认证登录</p>
      <el-button type="primary" size="large" :loading="loading" @click="handleLogin"
        >Casdoor 登录</el-button
      >
      <p v-if="error" class="error">{{ error }}</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const loading = ref(false)
const error = ref('')

onMounted(async () => {
  const code = route.query.code as string
  const state = route.query.state as string
  if (!code) return

  // Casdoor 回调：code 换 JWT
  loading.value = true
  try {
    const savedState = localStorage.getItem('oauth_state')
    if (state && savedState !== state) throw new Error('State 校验失败')
    await auth.exchangeCode(code)
    router.replace('/')
  } catch (e: any) {
    error.value = '登录失败: ' + (e.message || '未知错误')
  } finally {
    loading.value = false
  }
})

function handleLogin() {
  loading.value = true
  auth.redirectToCasdoor()
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
}
.login-card {
  width: 420px;
  text-align: center;
}
.login-card h2 {
  margin-bottom: 8px;
}
.subtitle {
  color: #909399;
  margin-bottom: 32px;
}
.error {
  color: #f56c6c;
  margin-top: 16px;
}
</style>
