import { apiFetch, parseApiResponse } from './http'

export async function getSettings() {
  const response = await apiFetch('/settings')
  return parseApiResponse(response, '设置加载失败')
}

export async function refreshServiceHealth() {
  const response = await apiFetch('/settings/services/health/refresh', { method: 'POST' })
  return parseApiResponse(response, '服务健康状态刷新失败')
}

export async function saveSettings(payload) {
  const response = await apiFetch('/settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '设置保存失败')
}

export async function restoreWorkspaceDefaults() {
  const response = await apiFetch('/settings/workspace/restore-defaults', { method: 'POST' })
  return parseApiResponse(response, '运行参数恢复失败')
}

export async function listMemories() {
  const response = await apiFetch('/settings/memories')
  return parseApiResponse(response, '记忆加载失败')
}

export async function addMemory(payload) {
  const response = await apiFetch('/settings/memories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '记忆保存失败')
}

export async function deleteMemory(memoryId) {
  const response = await apiFetch(`/settings/memories/${encodeURIComponent(memoryId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '记忆删除失败')
}

export async function clearMemories() {
  const response = await apiFetch('/settings/memories', { method: 'DELETE' })
  return parseApiResponse(response, '记忆清空失败')
}
