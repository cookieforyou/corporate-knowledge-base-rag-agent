import axios from 'axios'
import router from '@/router'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 300_000
})

// 请求拦截：注入 JWT
api.interceptors.request.use(config => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截：401 跳登录
api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('access_token')
      router.push('/login')
    }
    return Promise.reject(err)
  }
)

/** 同步问答 */
export const chat = (query: string) =>
  api.post('/chat', { query }).then(r => r.data.data.answer)

/** SSE 流式问答 */
export const chatStreamUrl = () =>
  `${api.defaults.baseURL}/chat/stream`

/** 文档上传 */
export const uploadDocument = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return api.post('/documents/upload', form).then(r => r.data.data.docId)
}

export default api
