import { apiUrl, parseApiResponse } from './http'

const TARGET_KEY = 'job-buddy.journey.target'
const RECORDS_KEY = 'job-buddy.journey.records'

function isNotFound(error) {
  return String(error?.message || error || '').includes('HTTP 404')
}

function defaultTarget() {
  return {
    targetId: 'target_local',
    companyNature: '互联网企业',
    companyScale: '不限',
    location: '上海',
    salaryRange: '40k-50k',
    domains: '大模型领域',
    positions: 'AI原生工程师 / AI应用研发工程师 / AI全栈研发工程师 / AI研发架构师 / AI研发项目负责人',
    preferredCompanies: '量化金融公司、米哈游、小红书、AI行业独角兽、小而精的AI创业公司、互联网大厂',
    notes: '',
    updatedAt: new Date().toISOString(),
    localOnly: true,
  }
}

function loadTargetLocal() {
  try { return JSON.parse(localStorage.getItem(TARGET_KEY)) || defaultTarget() } catch (_) { return defaultTarget() }
}
function saveTargetLocal(payload) {
  const saved = { ...payload, targetId: payload.targetId || 'target_local', updatedAt: new Date().toISOString(), localOnly: true }
  localStorage.setItem(TARGET_KEY, JSON.stringify(saved))
  return saved
}
function loadRecordsLocal() {
  try { return JSON.parse(localStorage.getItem(RECORDS_KEY)) || [] } catch (_) { return [] }
}
function saveRecordsLocal(rows) { localStorage.setItem(RECORDS_KEY, JSON.stringify(rows)) }
function matchFilter(row, params = {}) {
  const keyword = String(params.keyword || '').toLowerCase()
  if (keyword && ![row.company, row.positionName, row.businessDirection, row.interviewContent].some(v => String(v || '').toLowerCase().includes(keyword))) return false
  if (params.status && row.status !== params.status) return false
  if (params.result && row.result !== params.result) return false
  return true
}
function saveRecordLocal(payload, recordId) {
  const rows = loadRecordsLocal()
  const now = new Date().toISOString()
  const id = recordId || `journey_local_${crypto.randomUUID?.() || Date.now()}`
  const saved = { ...payload, recordId: id, updatedAt: now, createdAt: payload.createdAt || now, localOnly: true }
  const idx = rows.findIndex(item => item.recordId === id)
  if (idx >= 0) rows.splice(idx, 1, saved)
  else rows.unshift(saved)
  saveRecordsLocal(rows)
  return saved
}

export async function getJourneyTarget() {
  try {
    const response = await fetch(apiUrl('/journey/target'))
    return await parseApiResponse(response, '求职目标加载失败')
  } catch (error) {
    if (isNotFound(error)) return loadTargetLocal()
    throw error
  }
}

export async function saveJourneyTarget(payload) {
  try {
    const response = await fetch(apiUrl('/journey/target'), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return await parseApiResponse(response, '求职目标保存失败')
  } catch (error) {
    if (isNotFound(error)) return saveTargetLocal(payload)
    throw error
  }
}

export async function listJourneyRecords(params = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.status) query.set('status', params.status)
  if (params.result) query.set('result', params.result)
  try {
    const response = await fetch(apiUrl(`/journey/records${query.toString() ? `?${query}` : ''}`))
    return (await parseApiResponse(response, '求职进展加载失败')) || []
  } catch (error) {
    if (isNotFound(error)) return loadRecordsLocal().filter(row => matchFilter(row, params))
    throw error
  }
}

export async function createJourneyRecord(payload) {
  try {
    const response = await fetch(apiUrl('/journey/records'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return await parseApiResponse(response, '求职记录保存失败')
  } catch (error) {
    if (isNotFound(error)) return saveRecordLocal(payload)
    throw error
  }
}

export async function updateJourneyRecord(recordId, payload) {
  try {
    const response = await fetch(apiUrl(`/journey/records/${encodeURIComponent(recordId)}`), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return await parseApiResponse(response, '求职记录更新失败')
  } catch (error) {
    if (isNotFound(error)) return saveRecordLocal(payload, recordId)
    throw error
  }
}

export async function deleteJourneyRecord(recordId) {
  try {
    const response = await fetch(apiUrl(`/journey/records/${encodeURIComponent(recordId)}`), { method: 'DELETE' })
    return await parseApiResponse(response, '求职记录删除失败')
  } catch (error) {
    if (isNotFound(error)) {
      saveRecordsLocal(loadRecordsLocal().filter(item => item.recordId !== recordId))
      return { recordId }
    }
    throw error
  }
}

export async function analyzeJourneyProgress(payload = {}) {
  try {
    const response = await fetch(apiUrl('/journey/analysis'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    return await parseApiResponse(response, '面试进展分析失败')
  } catch (error) {
    if (isNotFound(error)) throw new Error('面试进展分析服务不可用，请确认后端服务已启动')
    throw error
  }
}
