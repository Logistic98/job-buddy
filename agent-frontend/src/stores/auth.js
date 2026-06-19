import { defineStore } from 'pinia'
import { currentUser, login as loginApi, logout as logoutApi } from '../api/auth'

const tokenKey = 'job_buddy_auth_token'
const userKey = 'job_buddy_auth_user'

function readJson(key) {
  try { return JSON.parse(window.localStorage.getItem(key) || 'null') } catch (_) { return null }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: window.localStorage.getItem(tokenKey) || '',
    user: readJson(userKey),
    loading: false,
    initialized: false,
    error: '',
  }),
  getters: {
    isLoggedIn: state => !!state.token && !!state.user,
    displayName: state => state.user?.displayName || state.user?.username || '用户',
  },
  actions: {
    async init() {
      if (!this.token) {
        this.initialized = true
        return
      }
      this.loading = true
      try {
        this.user = await currentUser(this.token)
        window.localStorage.setItem(userKey, JSON.stringify(this.user))
      } catch (_) {
        this.clearLocal()
      } finally {
        this.loading = false
        this.initialized = true
      }
    },
    async login(username, password) {
      this.loading = true
      this.error = ''
      try {
        const data = await loginApi(username, password)
        this.token = data.token || ''
        this.user = data.user || null
        window.localStorage.setItem(tokenKey, this.token)
        window.localStorage.setItem(userKey, JSON.stringify(this.user))
        return true
      } catch (error) {
        this.error = error?.message || '登录失败'
        return false
      } finally {
        this.loading = false
        this.initialized = true
      }
    },
    async logout() {
      const oldToken = this.token
      this.clearLocal()
      if (oldToken) await logoutApi(oldToken).catch(() => {})
    },
    clearLocal() {
      this.token = ''
      this.user = null
      window.localStorage.removeItem(tokenKey)
      window.localStorage.removeItem(userKey)
    },
  },
})
