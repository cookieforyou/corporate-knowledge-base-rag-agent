<template>
  <div class="shell">
    <!-- ══ 侧边导航 ══ -->
    <aside class="side">
      <div class="brand">
        <div class="brand-mark t-display">知</div>
        <div class="brand-text">
          <div class="brand-name t-display">KB RAG</div>
          <div class="brand-sub">企业知识库智能工作台</div>
        </div>
      </div>

      <div class="t-label nav-label">工作台</div>
      <nav class="nav">
        <router-link v-for="item in navItems" :key="item.to" :to="item.to" class="nav-item"
          :class="{ active: route.path === item.to }">
          <el-icon :size="17"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
          <i class="nav-ind" />
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="engine-chip">
          <span class="pulse-dot" />
          <div>
            <div class="engine-title">混合检索引擎</div>
            <div class="engine-sub">向量 · BM25 · RRF · Rerank</div>
          </div>
        </div>
        <div class="user-card">
          <div class="user-avatar">{{ userInitial }}</div>
          <div class="user-meta">
            <div class="user-name">{{ userName }}</div>
            <div class="user-tenant t-data">{{ tenantId }}</div>
          </div>
          <el-tooltip content="退出登录" placement="top">
            <el-button text class="logout-btn" @click="auth.logout()">
              <el-icon><SwitchButton /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
    </aside>

    <!-- ══ 主工作区 ══ -->
    <div class="workarea">
      <router-view v-slot="{ Component }">
        <transition name="view-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  ChatLineSquare, FolderOpened, DataAnalysis, Grid, SwitchButton, Odometer
} from '@element-plus/icons-vue'

const route = useRoute()
const auth = useAuthStore()

const navItems = [
  { to: '/', label: '智能问答', icon: ChatLineSquare },
  { to: '/documents', label: '文档管理', icon: FolderOpened },
  { to: '/debug', label: '检索调试', icon: DataAnalysis },
  { to: '/chunks', label: 'Chunk 观测', icon: Grid },
  { to: '/admin', label: '运维中心', icon: Odometer }
]

/** 解析 JWT payload（不校验，仅展示） */
const claims = computed(() => {
  try {
    const raw = localStorage.getItem('access_token')
    if (!raw) return {}
    return JSON.parse(atob(raw.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
  } catch {
    return {}
  }
})

const userName = computed(() => claims.value.name || claims.value.displayName || '用户')
const tenantId = computed(() => claims.value.owner || '—')
const userInitial = computed(() => String(userName.value).slice(0, 1).toUpperCase())
</script>

<style scoped>
.shell { display: flex; height: 100vh; overflow: hidden; }

/* ── 侧边：松墨深底 + 氛围光 ── */
.side {
  width: 236px; flex-shrink: 0; display: flex; flex-direction: column;
  background:
    radial-gradient(320px 200px at -20% -6%, rgba(217, 164, 65, .13), transparent 62%),
    linear-gradient(170deg, #12332C 0%, #0C241F 58%, #091B17 100%);
  color: #E7EFEC;
  padding: 22px 14px 16px;
}

.brand { display: flex; align-items: center; gap: 12px; padding: 2px 8px 20px; }
.brand-mark {
  width: 40px; height: 40px; border-radius: 11px; flex-shrink: 0;
  display: grid; place-items: center;
  font-size: 21px; color: var(--gold-100);
  background: linear-gradient(150deg, #1E7260, #123A32);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.18), 0 4px 14px rgba(0,0,0,.35);
  border: 1px solid rgba(235, 196, 122, .35);
}
.brand-name { font-size: 19px; font-weight: 900; letter-spacing: .04em; line-height: 1.1; }
.brand-sub { font-size: 11px; color: #93ACA4; margin-top: 3px; letter-spacing: .05em; }

.nav-label { padding: 0 12px 8px; color: #6E8A82; }
.nav { display: flex; flex-direction: column; gap: 3px; }
.nav-item {
  position: relative; display: flex; align-items: center; gap: 11px;
  padding: 10px 12px; border-radius: 9px; text-decoration: none;
  color: #B9CCC6; font-size: 14px; font-weight: 500;
  transition: background .2s var(--ease), color .2s var(--ease), transform .2s var(--ease);
}
.nav-item:hover { background: rgba(255,255,255,.06); color: #EAF2EF; transform: translateX(2px); }
.nav-item.active {
  background: rgba(30, 114, 96, .38); color: #fff; font-weight: 600;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.08);
}
.nav-ind {
  position: absolute; left: -14px; top: 50%; transform: translateY(-50%);
  width: 3px; height: 0; border-radius: 0 3px 3px 0;
  background: var(--gold-500); transition: height .25s var(--ease);
}
.nav-item.active .nav-ind { height: 22px; }

.side-foot { margin-top: auto; display: flex; flex-direction: column; gap: 12px; }

.engine-chip {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border-radius: 10px;
  background: rgba(255,255,255,.045); border: 1px solid rgba(255,255,255,.08);
}
.pulse-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
  background: #5FD3A5; box-shadow: 0 0 0 0 rgba(95, 211, 165, .5);
  animation: pulse 2.2s ease-out infinite;
}
@keyframes pulse {
  0%   { box-shadow: 0 0 0 0 rgba(95, 211, 165, .45); }
  70%  { box-shadow: 0 0 0 7px rgba(95, 211, 165, 0); }
  100% { box-shadow: 0 0 0 0 rgba(95, 211, 165, 0); }
}
.engine-title { font-size: 12.5px; font-weight: 600; color: #DCE9E5; }
.engine-sub { font-size: 10.5px; color: #7E988F; margin-top: 1px; letter-spacing: .03em; }

.user-card {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 10px;
  background: rgba(255,255,255,.045); border: 1px solid rgba(255,255,255,.08);
}
.user-avatar {
  width: 32px; height: 32px; border-radius: 9px; flex-shrink: 0;
  display: grid; place-items: center;
  background: linear-gradient(150deg, var(--gold-500), #A9781F);
  color: #1C2725; font-weight: 800; font-size: 14px;
}
.user-meta { flex: 1; min-width: 0; }
.user-name { font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-tenant { font-size: 10.5px; color: #7E988F; }
.logout-btn { color: #93ACA4; }
.logout-btn:hover { color: #fff; }

.workarea { flex: 1; min-width: 0; display: flex; flex-direction: column; }
</style>
