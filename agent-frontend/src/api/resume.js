import { apiUrl, parseApiResponse } from './http'

export async function listResumes() {
  const response = await fetch(apiUrl('/resume'))
  return (await parseApiResponse(response, '简历列表加载失败')) || []
}

export async function getJobProfile() {
  const response = await fetch(apiUrl('/resume/profile'))
  return parseApiResponse(response, '求职画像加载失败')
}

export async function saveJobProfile(parsed) {
  const response = await fetch(apiUrl('/resume/profile'), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parsed }),
  })
  return parseApiResponse(response, '求职画像保存失败')
}

export async function generateJobProfileSummary(parsed, sessionId) {
  const params = new URLSearchParams()
  if (sessionId) params.set('sessionId', sessionId)
  const response = await fetch(apiUrl(`/resume/profile/summary${params.toString() ? `?${params.toString()}` : ''}`), {
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
    const response = await fetch(apiUrl('/resume/boss/sync'), { method: 'POST', signal: controller.signal })
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
  const response = await fetch(apiUrl('/resume/upload'), { method: 'POST', body: form })
  return parseApiResponse(response, '简历上传失败')
}

export async function uploadResumeAsset(file) {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch(apiUrl('/resume/assets/upload'), { method: 'POST', body: form })
  const data = await parseApiResponse(response, '资源上传失败')
  return { ...data, url: data?.url?.startsWith('/api') ? data.url : apiUrl(data.url || '') }
}

export async function getResume(resumeId) {
  const response = await fetch(apiUrl(`/resume/${encodeURIComponent(resumeId)}`))
  return parseApiResponse(response, '简历查询失败')
}

export async function analyzeResume(resumeId, sessionId) {
  const params = new URLSearchParams({ resumeId })
  if (sessionId) params.set('sessionId', sessionId)
  const response = await fetch(apiUrl(`/resume/analyze?${params.toString()}`), { method: 'POST' })
  return parseApiResponse(response, '简历分析失败')
}

export async function updateResumeParsed(resumeId, parsed) {
  const response = await fetch(apiUrl(`/resume/${encodeURIComponent(resumeId)}/parsed`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ parsed }),
  })
  return parseApiResponse(response, '简历解析结果保存失败')
}

export async function deleteResume(resumeId) {
  const response = await fetch(apiUrl(`/resume/${encodeURIComponent(resumeId)}`), { method: 'DELETE' })
  return parseApiResponse(response, '简历删除失败')
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
