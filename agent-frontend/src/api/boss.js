import { apiUrl, parseApiResponse } from './http'

function buildQuery(params) {
  const entries = Object.entries(params || {}).filter(([, value]) => value)
  if (!entries.length) return ''
  return '?' + entries.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&')
}

export async function getBossLoginQr(sessionId) {
  const response = await fetch(apiUrl(`/boss/login-qr${buildQuery({ sessionId })}`))
  return parseApiResponse(response, '获取登录二维码失败')
}

export async function getBossLoginStatus(sessionId, qrSessionId) {
  const response = await fetch(apiUrl(`/boss/login-status${buildQuery({ sessionId, qrSessionId })}`))
  return parseApiResponse(response, '获取登录状态失败')
}

export async function cancelBossLogin(sessionId, qrSessionId) {
  const response = await fetch(apiUrl(`/boss/login-cancel${buildQuery({ sessionId, qrSessionId })}`), {
    method: 'POST',
  })
  return parseApiResponse(response, '取消登录失败')
}
