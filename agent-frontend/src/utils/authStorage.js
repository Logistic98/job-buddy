export const AUTH_USER_KEY = 'job_buddy_auth_user'
export const AUTH_SYNC_CHANNEL = 'job_buddy_auth_sync'

const AUTH_LOGOUT_EVENT = 'logout'

function readSessionValue(key) {
  try {
    return window.sessionStorage.getItem(key) || ''
  } catch (_) {
    return ''
  }
}

function writeSessionValue(key, value) {
  try {
    window.sessionStorage.setItem(key, value)
  } catch (_) {
    // HttpOnly Cookie 是浏览器会话的唯一凭据，用户缓存不可用不影响认证。
  }
}

export function readAuthUser() {
  try {
    return JSON.parse(readSessionValue(AUTH_USER_KEY) || 'null')
  } catch (_) {
    return null
  }
}

export function saveAuthUser(user) {
  writeSessionValue(AUTH_USER_KEY, JSON.stringify(user || null))
}

export function clearAuthSession() {
  try {
    window.sessionStorage.removeItem(AUTH_USER_KEY)
  } catch (_) {
    // 后端在退出登录时清除 HttpOnly Cookie。
  }
}

export function publishAuthLogout() {
  if (typeof BroadcastChannel === 'undefined') return
  const channel = new BroadcastChannel(AUTH_SYNC_CHANNEL)
  try {
    channel.postMessage({
      type: AUTH_LOGOUT_EVENT,
      nonce: `${Date.now()}-${Math.random()}`,
    })
  } finally {
    channel.close()
  }
}

export function subscribeAuthLogout(onLogout) {
  if (typeof onLogout !== 'function' || typeof BroadcastChannel === 'undefined') return () => {}

  const channel = new BroadcastChannel(AUTH_SYNC_CHANNEL)
  channel.onmessage = (event) => {
    if (event.data?.type === AUTH_LOGOUT_EVENT) onLogout()
  }
  return () => channel.close()
}
