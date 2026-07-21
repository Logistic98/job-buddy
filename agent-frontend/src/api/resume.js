import { apiFetch, apiUrl, parseApiResponse } from './http'
import { getAnalysisTask, getLatestAnalysisTask, streamAnalysisTask } from './analysisTasks'

export async function listResumes() {
  const response = await apiFetch('/resume')
  return (await parseApiResponse(response, '简历列表加载失败')) || []
}

export async function getJobProfile() {
  const response = await apiFetch('/resume/profile')
  return parseApiResponse(response, '求职画像加载失败')
}

export async function saveJobProfile(parsed) {
  const response = await apiFetch('/resume/profile', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parsed }),
  })
  return parseApiResponse(response, '求职画像保存失败')
}

export async function generateJobProfileSummary(parsed, sessionId) {
  const params = new URLSearchParams()
  if (sessionId) params.set('sessionId', sessionId)
  const response = await apiFetch(`/resume/profile/summary${params.toString() ? `?${params.toString()}` : ''}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parsed }),
  })
  return parseApiResponse(response, 'AI 画像摘要生成失败')
}

export async function syncBossOnlineResume() {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), 45000)
  try {
    const response = await apiFetch('/resume/boss/sync', { method: 'POST', signal: controller.signal })
    return parseApiResponse(response, '从 Boss 直聘拉取求职画像失败')
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('从 Boss 直聘拉取求职画像超时，请确认登录态正常后重试。')
    throw error
  } finally {
    window.clearTimeout(timer)
  }
}

export async function uploadResume(file, sessionId) {
  const form = new FormData()
  form.append('file', file)
  if (sessionId) form.append('sessionId', sessionId)
  const response = await apiFetch('/resume/upload', { method: 'POST', body: form })
  return parseApiResponse(response, '简历上传失败')
}

export async function uploadResumeAsset(file) {
  const form = new FormData()
  form.append('file', file)
  const response = await apiFetch('/resume/assets/upload', { method: 'POST', body: form })
  const data = await parseApiResponse(response, '资源上传失败')
  const rawUrl = data?.url?.startsWith('/api') ? data.url : apiUrl(data?.url || '')
  return { ...data, url: normalizeResumeAssetUrl(rawUrl) }
}

export function normalizeResumeAssetUrl(value) {
  const rawUrl = String(value || '').trim()
  if (!rawUrl || rawUrl.startsWith('//')) return ''
  if (/^blob:/i.test(rawUrl) || /^data:image\/(?:png|jpe?g|gif|webp);base64,/i.test(rawUrl)) return rawUrl
  const absolute = /^[a-z][a-z\d+.-]*:/i.test(rawUrl)
  let parsed
  try {
    parsed = new URL(rawUrl, 'http://job-buddy.local')
  } catch (_) {
    return ''
  }
  if (absolute && !['http:', 'https:'].includes(parsed.protocol)) return ''
  return absolute ? parsed.toString() : `${parsed.pathname}${parsed.search}${parsed.hash}`
}

export function resumeAssetDisplayUrl(value) {
  return normalizeResumeAssetUrl(value)
}

export async function getResume(resumeId) {
  const response = await apiFetch(`/resume/${encodeURIComponent(resumeId)}`)
  return parseApiResponse(response, '简历查询失败')
}

export async function startResumeAnalysisTask(resumeId, sessionId) {
  const response = await apiFetch('/resume/analysis-tasks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ resumeId, sessionId: sessionId || '' }),
  })
  return parseApiResponse(response, '简历分析任务创建失败')
}

export function latestResumeAnalysisTask(resumeId) {
  return getLatestAnalysisTask('resume', resumeId)
}

export { getAnalysisTask, streamAnalysisTask }

export async function updateResumeParsed(resumeId, parsed) {
  const response = await apiFetch(`/resume/${encodeURIComponent(resumeId)}/parsed`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parsed }),
  })
  return parseApiResponse(response, '简历解析结果保存失败')
}

export async function deleteResume(resumeId) {
  const response = await apiFetch(`/resume/${encodeURIComponent(resumeId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '简历删除失败')
}

export async function listWriterVersions() {
  const response = await apiFetch('/resume/writer/versions')
  return (await parseApiResponse(response, '版本列表加载失败')) || []
}

export async function getWriterVersion(versionId) {
  const response = await apiFetch(`/resume/writer/versions/${encodeURIComponent(versionId)}`)
  return parseApiResponse(response, '版本详情加载失败')
}

export async function createWriterVersion({ source, title, resumeId, snapshot }) {
  const response = await apiFetch('/resume/writer/versions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ source, title, resumeId, snapshot }),
  })
  return parseApiResponse(response, '版本保存失败')
}

export async function restoreWriterVersion(versionId, { currentSnapshot, currentResumeId }) {
  const response = await apiFetch(`/resume/writer/versions/${encodeURIComponent(versionId)}/restore`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currentSnapshot, currentResumeId }),
  })
  return parseApiResponse(response, '版本回退失败')
}

export async function deleteWriterVersion(versionId) {
  const response = await apiFetch(`/resume/writer/versions/${encodeURIComponent(versionId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '版本删除失败')
}

export function resumePreviewUrl(resumeId) {
  return apiUrl(`/resume/${encodeURIComponent(resumeId)}/preview`)
}

export function resumeThumbnailUrl(resumeId) {
  return apiUrl(`/resume/${encodeURIComponent(resumeId)}/thumbnail`)
}

export function resumeDownloadUrl(resumeId) {
  return apiUrl(`/resume/${encodeURIComponent(resumeId)}/download`)
}
