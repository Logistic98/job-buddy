import { apiFetch, parseApiResponse } from './http'
import { streamSse } from './sse'

export async function askAgent(payload) {
  const response = await apiFetch('/chat/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '请求失败')
}

export async function listSessions() {
  const response = await apiFetch('/chat/sessions')
  return (await parseApiResponse(response, '加载会话失败')) || []
}

export async function listSessionMessages(sessionId) {
  const response = await apiFetch(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`)
  return (await parseApiResponse(response, '加载消息失败')) || []
}

export async function deleteSession(sessionId) {
  const response = await apiFetch(`/chat/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '删除会话失败')
}

export function streamChat(payload, handlers = {}) {
  return streamSse(
    '/chat/stream',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      requireDone: true,
      reconnect: false,
    },
    handlers,
  )
}
