import { apiFetch, parseApiResponse } from './http'

export async function getJourneyTarget() {
  const response = await apiFetch('/journey/target')
  return parseApiResponse(response, '求职目标加载失败')
}

export async function saveJourneyTarget(payload) {
  const response = await apiFetch('/journey/target', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '求职目标保存失败')
}

export async function listJourneyRecords(params = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.status) query.set('status', params.status)
  if (params.result) query.set('result', params.result)
  const response = await apiFetch(`/journey/records${query.toString() ? `?${query}` : ''}`)
  return (await parseApiResponse(response, '求职进展加载失败')) || []
}

export async function createJourneyRecord(payload) {
  const response = await apiFetch('/journey/records', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '求职记录保存失败')
}

export async function updateJourneyRecord(recordId, payload) {
  const response = await apiFetch(`/journey/records/${encodeURIComponent(recordId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '求职记录更新失败')
}

export async function deleteJourneyRecord(recordId) {
  const response = await apiFetch(`/journey/records/${encodeURIComponent(recordId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '求职记录删除失败')
}

export async function analyzeJourneyProgress(payload = {}) {
  const response = await apiFetch('/journey/analysis', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '面试进展分析失败')
}
