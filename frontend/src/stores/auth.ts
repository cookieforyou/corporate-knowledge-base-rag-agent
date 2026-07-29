import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

const AUTH_URL = import.meta.env.VITE_AUTH_URL
const CLIENT_ID = import.meta.env.VITE_AUTH_CLIENT_ID
const REDIRECT_URI = window.location.origin + '/login'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('access_token') || '')

  const isAuthenticated = computed(() => !!token.value)

  function saveToken(jwt: string) {
    token.value = jwt
    localStorage.setItem('access_token', jwt)
  }

  function logout() {
    token.value = ''
    localStorage.removeItem('access_token')
    window.location.href = '/login'
  }

  /** 生成 PKCE code_verifier + code_challenge */
  function generatePKCE() {
    const array = new Uint8Array(32)
    crypto.getRandomValues(array)
    const verifier = btoa(String.fromCharCode(...array))
      .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
    localStorage.setItem('code_verifier', verifier)

    // SHA-256 hash → code_challenge
    return crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
      .then(buf => btoa(String.fromCharCode(...new Uint8Array(buf)))
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''))
  }

  /** 跳转 Casdoor 登录页 */
  async function redirectToCasdoor() {
    const challenge = await generatePKCE()
    const state = crypto.randomUUID()
    localStorage.setItem('oauth_state', state)

    const params = new URLSearchParams({
      client_id: CLIENT_ID,
      response_type: 'code',
      redirect_uri: REDIRECT_URI,
      scope: 'read',
      state,
      code_challenge_method: 'S256',
      code_challenge: challenge
    })
    window.location.href = `${AUTH_URL}/login/oauth/authorize?${params}`
  }

  /** 用 code 换取 JWT（PKCE 流程） */
  async function exchangeCode(code: string): Promise<void> {
    const verifier = localStorage.getItem('code_verifier') || ''
    const state = localStorage.getItem('oauth_state') || ''
    localStorage.removeItem('code_verifier')
    localStorage.removeItem('oauth_state')

    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: CLIENT_ID,
      code,
      redirect_uri: REDIRECT_URI,
      code_verifier: verifier,
      state
    })

    const res = await fetch(`${AUTH_URL}/api/login/oauth/access_token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body
    })

    if (!res.ok) throw new Error('Token 交换失败')

    const data = await res.json()
    saveToken(data.access_token)
  }

  return { token, isAuthenticated, saveToken, logout, redirectToCasdoor, exchangeCode }
})
