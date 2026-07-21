import { apiFetch, parseApiResponse } from './http'

export async function getWorkspaceState(stateKey) {
  const response = await apiFetch(`/workspace/state/${encodeURIComponent(stateKey)}`)
  return (await parseApiResponse(response, '工作区状态加载失败')) || {}
}

export async function saveWorkspaceState(stateKey, state) {
  const response = await apiFetch(`/workspace/state/${encodeURIComponent(stateKey)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(state || {}),
  })
  return parseApiResponse(response, '工作区状态保存失败')
}

export async function deleteWorkspaceState(stateKey) {
  const response = await apiFetch(`/workspace/state/${encodeURIComponent(stateKey)}`, { method: 'DELETE' })
  return parseApiResponse(response, '工作区状态删除失败')
}
