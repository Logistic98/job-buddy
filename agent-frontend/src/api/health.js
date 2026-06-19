import { apiUrl, parseApiResponse } from './http'

export async function getSystemHealth({ timeoutMs = 1800 } = {}) {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(apiUrl('/health'), {
      cache: 'no-store',
      headers: { 'Cache-Control': 'no-cache' },
      signal: controller.signal,
    })
    return parseApiResponse(response, '系统健康检查失败')
  } finally {
    window.clearTimeout(timer)
  }
}
