<template>
  <div class="login-shell">
    <!-- ══ 品牌面板 ══ -->
    <aside class="brand-panel">
      <div class="brand-glow" />
      <div class="brand-top reveal">
        <div class="brand-mark t-display">知</div>
        <div>
          <div class="brand-name t-display">KB RAG</div>
          <div class="brand-sub">企业知识库智能工作台</div>
        </div>
      </div>

      <div class="brand-body">
        <h1 class="brand-headline t-display reveal" style="--d:.08s">
          让企业知识<br /><em>可检索、可溯源、可度量</em>
        </h1>
        <ul class="brand-points">
          <li class="reveal" style="--d:.16s">
            <i class="point-bar" />
            <div><b>多路混合检索</b><span>向量语义 + BM25 关键词 + 图谱实体，RRF 融合 + qwen3-rerank 精排</span></div>
          </li>
          <li class="reveal" style="--d:.24s">
            <i class="point-bar" />
            <div><b>溯源式对话</b><span>回答逐句附 [ref-N] 证据引用，检索全链路得分可透视</span></div>
          </li>
          <li class="reveal" style="--d:.32s">
            <i class="point-bar" />
            <div><b>质量可度量</b><span>Golden Dataset 自动评估，Recall / MRR / 拒答率持续门禁</span></div>
          </li>
        </ul>
      </div>

      <div class="brand-foot reveal" style="--d:.4s">
        <span class="t-data">Spring AI 2.0 · GLM-5.3-Flash · Milvus · Elasticsearch · Neo4j</span>
      </div>
    </aside>

    <!-- ══ 登录面板 ══ -->
    <main class="login-panel">
      <div class="login-card panel reveal" style="--d:.12s">
        <div class="t-label login-kicker">统一身份认证</div>
        <h2 class="login-title t-display">登录工作台</h2>
        <p class="login-desc">使用 Casdoor 企业账号登录，租户数据严格隔离</p>

        <el-button class="login-btn" size="large" :loading="loading" @click="handleLogin">
          <el-icon v-if="!loading"><Key /></el-icon>&nbsp;
          {{ loading ? '正在跳转认证…' : 'Casdoor 登录' }}
        </el-button>

        <transition name="slide-fade">
          <div v-if="error" class="login-error">
            <el-icon><CircleCloseFilled /></el-icon>{{ error }}
          </div>
        </transition>

        <div class="login-secure">
          <el-icon><Lock /></el-icon>
          OAuth2 / PKCE 授权流程 · JWT 无状态会话
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { Key, Lock, CircleCloseFilled } from '@element-plus/icons-vue'

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
    error.value = '登录失败：' + (e.message || '未知错误')
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
.login-shell { display: flex; height: 100vh; overflow: hidden; }

/* ── 品牌面板：松墨深底 + 氛围光 + 点阵 ── */
.brand-panel {
  position: relative; flex: 1.15; min-width: 480px;
  display: flex; flex-direction: column;
  padding: 40px 48px;
  color: #E7EFEC; overflow: hidden;
  background:
    radial-gradient(720px 420px at -10% -10%, rgba(217, 164, 65, .16), transparent 58%),
    radial-gradient(640px 480px at 110% 110%, rgba(30, 114, 96, .5), transparent 60%),
    linear-gradient(165deg, #13352E 0%, #0C241F 55%, #081C18 100%);
}
.brand-glow {
  position: absolute; inset: 0; pointer-events: none;
  background-image: radial-gradient(rgba(231, 239, 236, .05) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: linear-gradient(160deg, transparent 30%, #000 75%);
}
.brand-top { position: relative; display: flex; align-items: center; gap: 14px; }
.brand-mark {
  width: 46px; height: 46px; border-radius: 13px;
  display: grid; place-items: center; font-size: 24px; color: var(--gold-100);
  background: linear-gradient(150deg, #1E7260, #123A32);
  border: 1px solid rgba(235, 196, 122, .4);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.18), 0 6px 18px rgba(0,0,0,.4);
}
.brand-name { font-size: 22px; font-weight: 900; letter-spacing: .05em; }
.brand-sub { font-size: 12px; color: #93ACA4; margin-top: 2px; letter-spacing: .06em; }

.brand-body { position: relative; margin: auto 0; }
.brand-headline {
  font-size: 40px; line-height: 1.32; margin: 0 0 36px; font-weight: 900;
  color: #F2F7F5;
}
.brand-headline em {
  font-style: normal;
  background: linear-gradient(100deg, var(--gold-300), var(--gold-500));
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.brand-points { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 18px; }
.brand-points li { display: flex; gap: 14px; align-items: flex-start; }
.point-bar {
  width: 3px; align-self: stretch; border-radius: 2px; flex-shrink: 0;
  background: linear-gradient(180deg, var(--gold-500), rgba(217, 164, 65, .15));
}
.brand-points b { display: block; font-size: 15px; color: #EAF2EF; margin-bottom: 3px; }
.brand-points span { font-size: 12.5px; color: #93ACA4; line-height: 1.6; }

.brand-foot { position: relative; font-size: 11px; color: #6E8A82; letter-spacing: .05em; }

/* ── 登录面板 ── */
.login-panel {
  flex: 1; display: grid; place-items: center; padding: 32px;
}
.login-card { width: min(400px, 100%); padding: 40px 38px 32px; }
.login-kicker { margin-bottom: 10px; }
.login-title { margin: 0; font-size: 27px; color: var(--pine-900); }
.login-desc { margin: 9px 0 28px; color: var(--ink-3); font-size: 13.5px; }
.login-btn {
  width: 100%; height: 46px; font-size: 15px; font-weight: 700;
  color: #F0F6F4 !important; border: none;
  background: linear-gradient(140deg, var(--pine-600), var(--pine-800)) !important;
  box-shadow: 0 6px 18px rgba(13, 43, 37, .28);
  transition: transform .2s var(--ease), box-shadow .2s var(--ease) !important;
}
.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 9px 24px rgba(13, 43, 37, .34) !important;
}
.login-error {
  margin-top: 16px; padding: 10px 13px; border-radius: 9px;
  background: #FAE9E8; border: 1px solid #F2CFCD; color: var(--danger);
  font-size: 13px; display: flex; align-items: center; gap: 7px;
}
.login-secure {
  margin-top: 26px; padding-top: 18px; border-top: 1px dashed var(--line);
  display: flex; align-items: center; gap: 7px;
  font-size: 11.5px; color: var(--ink-3);
}

.slide-fade-enter-active { transition: all .3s var(--ease); }
.slide-fade-leave-active { transition: all .2s ease; }
.slide-fade-enter-from, .slide-fade-leave-to { opacity: 0; transform: translateY(-6px); }

@media (max-width: 960px) {
  .brand-panel { display: none; }
}
</style>
