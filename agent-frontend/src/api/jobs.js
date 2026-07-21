import { apiFetch, parseApiResponse } from './http'
import { getAnalysisTask, getLatestAnalysisTask, streamAnalysisTask } from './analysisTasks'

export async function listFavoriteJobs() {
  const response = await apiFetch('/jobs/favorites', { cache: 'no-store', headers: { 'Cache-Control': 'no-cache' } })
  return (await parseApiResponse(response, '加载岗位收藏失败')) || []
}

export async function listBossFavoriteJobs(page = 1, refresh = false) {
  const params = new URLSearchParams({ page: String(Math.max(1, Number(page) || 1)) })
  if (refresh) params.set('refresh', 'true')
  const response = await apiFetch(`/jobs/favorites/boss?${params.toString()}`, {
    cache: 'no-store',
    headers: { 'Cache-Control': 'no-cache' },
  })
  return parseApiResponse(response, '读取 Boss 收藏岗位失败')
}

export async function importBossFavoriteJobs(jobs) {
  const response = await apiFetch('/jobs/favorites/boss/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jobs: Array.isArray(jobs) ? jobs : [] }),
  })
  return parseApiResponse(response, '导入 Boss 收藏岗位失败')
}

export async function saveFavoriteJob(job) {
  const response = await apiFetch('/jobs/favorites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(job || {}),
  })
  return (await parseApiResponse(response, '保存岗位收藏失败')) || []
}

export async function startFavoriteAnalysisTask(job, resumeId) {
  const jobKey = String(job?.favoriteKey || job?.securityId || job?.id || job?.jobId || job?.encryptJobId || '').trim()
  const body = { ...(job || {}), jobKey }
  if (resumeId) body.resumeId = resumeId
  const response = await apiFetch('/jobs/favorites/analysis-tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse(response, '收藏岗位分析任务创建失败')
}

export function latestFavoriteAnalysisTask(jobKey) {
  return getLatestAnalysisTask('favorite_job', jobKey)
}

export { getAnalysisTask, streamAnalysisTask }

/**
 * 分析会话推荐岗位与当前简历的匹配度。岗位卡片通常未收藏、没有持久化 jobKey，
 * 因此整条岗位快照随请求体一起提交，由后端做临时匹配分析并返回结果（命中收藏才落库）。
 */
export async function analyzeJobByBody(job, resumeId) {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 120000)
  const body = { ...(job || {}) }
  if (resumeId) body.resumeId = resumeId
  try {
    const response = await apiFetch('/jobs/favorites/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    })
    return parseApiResponse(response, '岗位分析失败')
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('岗位分析超时，请稍后重试。')
    throw error
  } finally {
    window.clearTimeout(timer)
  }
}

export async function deleteFavoriteJob(jobKey) {
  const response = await apiFetch(`/jobs/favorites/${encodeURIComponent(jobKey)}`, { method: 'DELETE' })
  return (await parseApiResponse(response, '移出岗位收藏失败')) || []
}

/**
 * 懒加载岗位详情（含职位描述 JD）。仅在用户点击查看时才访问 Boss，降低风控风险。
 * 未登录时后端返回 code=4001，这里抛出带 authRequired 标记的错误，由调用方触发扫码登录。
 */
export async function fetchJobDetail(securityId, url) {
  const params = new URLSearchParams()
  if (securityId) params.set('securityId', securityId)
  if (url) params.set('url', url)
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 90000)
  try {
    const response = await apiFetch(`/jobs/detail?${params.toString()}`, {
      cache: 'no-store',
      headers: { 'Cache-Control': 'no-cache' },
      signal: controller.signal,
    })
    const text = await response.text()
    let json = null
    if (text) {
      try {
        json = JSON.parse(text)
      } catch (_) {
        throw new Error(`获取岗位详情失败: HTTP ${response.status} ${text.slice(0, 200)}`)
      }
    }
    if (!json) throw new Error(`获取岗位详情失败: HTTP ${response.status}, 响应体为空`)
    if (json.code === 4001) {
      const error = new Error(json.message || 'Boss 直聘需要重新扫码登录')
      error.authRequired = true
      error.authData = json.data || null
      throw error
    }
    if (!response.ok || (json.code !== 0 && json.code !== 200)) {
      throw new Error(json.message || `获取岗位详情失败: HTTP ${response.status}`)
    }
    return json.data
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('获取岗位详情超时，请稍后重试。')
    throw error
  } finally {
    window.clearTimeout(timer)
  }
}
