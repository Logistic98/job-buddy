import { apiUrl, parseApiResponse } from './http'

export async function askAgent(payload) {
  const response = await fetch(apiUrl('/chat/ask'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '请求失败')
}

export async function listSessions() {
  const response = await fetch(apiUrl('/chat/sessions'))
  return (await parseApiResponse(response, '加载会话失败')) || []
}

export async function listSessionMessages(sessionId) {
  const response = await fetch(apiUrl(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`))
  return (await parseApiResponse(response, '加载消息失败')) || []
}

export async function deleteSession(sessionId) {
  const response = await fetch(apiUrl(`/chat/sessions/${encodeURIComponent(sessionId)}`), { method: 'DELETE' })
  return parseApiResponse(response, '删除会话失败')
}

export async function streamChat(payload, handlers = {}) {
  let response
  try {
    response = await fetch(apiUrl('/chat/stream'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(payload),
      signal: handlers.signal,
    })
  } catch (error) {
    throw new Error(`服务连接失败：${error?.message || error}`)
  }

  if (!response.ok || !response.body) {
    const detail = await readErrorBody(response)
    throw new Error(detail || `SSE 请求失败: HTTP ${response.status}`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) dispatchSse(part, handlers)
    }
    if (buffer.trim()) dispatchSse(buffer, handlers)
  } finally {
    try { await reader.cancel() } catch (_) {}
  }
}

async function readErrorBody(response) {
  try {
    const text = await response.text()
    if (!text) return ''
    const data = JSON.parse(text)
    return data.message || data.detail || text.slice(0, 200)
  } catch (_) {
    return `SSE 请求失败: HTTP ${response.status}`
  }
}

function dispatchSse(raw, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) {
      // 按 SSE 规范只剥离 data: 后的单个前导空格，不能整体 trim：
      // 否则非 JSON 的原始文本增量里有意义的首尾空白（缩进、词间空格）会被吞掉，导致 token 粘连。
      let value = line.slice(5)
      if (value.startsWith(' ')) value = value.slice(1)
      dataLines.push(value)
    }
  }
  const text = dataLines.join('\n')
  let data = text
  try { data = JSON.parse(text) } catch (_) {}
  try { handlers.onEvent?.(event, data) } catch (error) { console.warn('[chat] SSE onEvent 处理失败', event, error) }
  try { handlers[event]?.(data) } catch (error) { console.warn('[chat] SSE 事件处理失败', event, error) }
}
