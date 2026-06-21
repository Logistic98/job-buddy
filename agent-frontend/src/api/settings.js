import { apiUrl, parseApiResponse } from './http'

export async function getSettings() {
  const response = await fetch(apiUrl('/settings'))
  return parseApiResponse(response, '设置加载失败')
}

export async function saveSettings(payload) {
  const response = await fetch(apiUrl('/settings'), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '设置保存失败')
}

export async function listMemories() {
  const response = await fetch(apiUrl('/settings/memories'))
  return parseApiResponse(response, '记忆加载失败')
}

export async function addMemory(payload) {
  const response = await fetch(apiUrl('/settings/memories'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '记忆保存失败')
}

export async function deleteMemory(memoryId) {
  const response = await fetch(apiUrl(`/settings/memories/${encodeURIComponent(memoryId)}`), { method: 'DELETE' })
  return parseApiResponse(response, '记忆删除失败')
}

export async function clearMemories() {
  const response = await fetch(apiUrl('/settings/memories'), { method: 'DELETE' })
  return parseApiResponse(response, '记忆清空失败')
}
