import { defineStore } from 'pinia'
import { currentUser, login as loginApi, logout as logoutApi } from '../api/auth'
import { clearAuthSession, publishAuthLogout, readAuthUser, saveAuthUser } from '../utils/authStorage'

let initPromise = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: readAuthUser(),
    loading: false,
    initialized: false,
    sessionRevision: 0,
    error: '',
  }),
  getters: {
    isLoggedIn: (state) => !!state.user,
    displayName: (state) => state.user?.displayName || state.user?.username || '用户',
    isAdmin: (state) =>
      ['users:manage', 'roles:manage', 'menus:manage', 'platform:manage'].some((code) =>
        state.user?.permissions?.includes(code),
      ),
    permissions: (state) => (Array.isArray(state.user?.permissions) ? state.user.permissions : []),
    roles: (state) => (Array.isArray(state.user?.roles) ? state.user.roles : []),
    menus: (state) => (Array.isArray(state.user?.menus) ? state.user.menus : []),
  },
  actions: {
    async init() {
      if (this.initialized) return
      if (initPromise) return initPromise
      this.loading = true
      const sessionRevision = this.sessionRevision
      initPromise = (async () => {
        try {
          const user = await currentUser()
          if (this.sessionRevision !== sessionRevision) return
          this.user = user
          saveAuthUser(this.user)
        } catch (_) {
          if (this.sessionRevision === sessionRevision) this.clearLocal()
        } finally {
          this.loading = false
          this.initialized = true
        }
      })()
      try {
        return await initPromise
      } finally {
        initPromise = null
      }
    },
    hasPermission(permission) {
      return this.permissions.includes(permission)
    },
    async login(username, password) {
      this.loading = true
      this.error = ''
      try {
        const data = await loginApi(username, password)
        this.sessionRevision += 1
        this.user = data.user || null
        saveAuthUser(this.user)
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
      this.clearLocal()
      publishAuthLogout()
      await logoutApi().catch(() => {})
    },
    clearLocal() {
      this.sessionRevision += 1
      this.user = null
      clearAuthSession()
    },
  },
})
