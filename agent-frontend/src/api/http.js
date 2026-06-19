const API_BASE = import.meta.env.VITE_API_BASE || '/api'

// Boss 直聘未登录/登录态失效的语义码，与后端统一响应保持一致。
export const AUTH_REQUIRED_CODE = 4001

export class ApiError extends Error {
  constructor(message, code, data) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.data = data
  }

  get authRequired() {
    return this.code === AUTH_REQUIRED_CODE
  }

  // 与既有 fetchJobDetail 错误约定保持一致：调用方用 error.authData 还原登录引导信息。
  get authData() {
    return this.data
  }
}

export function apiUrl(path) {
  return `${API_BASE}${path}`
}

export async function parseApiResponse(response, fallbackMessage) {
  const text = await response.text()
  let json = null

  if (text) {
    try {
      json = JSON.parse(text)
    } catch (error) {
      const message = response.ok
        ? `接口返回了非 JSON 内容: ${text.slice(0, 200)}`
        : `${fallbackMessage}: HTTP ${response.status} ${text.slice(0, 200)}`
      throw new Error(message)
    }
  }

  if (!json) {
    throw new Error(`${fallbackMessage}: HTTP ${response.status}, 响应体为空`)
  }

  if (!response.ok || json.code !== 0) {
    // 抛出携带 code 与 data 的结构化错误，调用方可据此区分登录态失效（4001）并触发扫码登录，
    // 而不是把所有失败都当成普通错误吞掉，导致岗位分析/收藏等操作不弹登录。
    throw new ApiError(
      json.message || `${fallbackMessage}: HTTP ${response.status}`,
      typeof json.code === 'number' ? json.code : undefined,
      json.data,
    )
  }

  return json.data
}
