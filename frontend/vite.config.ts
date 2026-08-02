import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.BACKEND_URL || 'http://localhost:8090',
          changeOrigin: true
        },
        // ETL 进度 WebSocket（2.13）：ws 代理至后端
        '/ws': {
          target: env.BACKEND_URL || 'http://localhost:8090',
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
