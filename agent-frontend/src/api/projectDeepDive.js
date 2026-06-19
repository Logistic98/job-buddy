import { apiUrl, parseApiResponse } from './http'

const LOCAL_KEY = 'job-buddy-project-deep-dive-local'

export async function listDeepDiveProjects() {
  const response = await fetch(apiUrl('/project-deep-dive/projects'))
  if (isUnsupported(response)) return loadLocalProjects()
  return (await parseApiResponse(response, '项目深挖列表加载失败')) || []
}

export async function createDeepDiveProject(payload) {
  const response = await fetch(apiUrl('/project-deep-dive/projects'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (isUnsupported(response)) return createLocalProject(payload)
  return parseApiResponse(response, '项目保存失败')
}

export async function deleteDeepDiveProject(projectId) {
  const response = await fetch(apiUrl(`/project-deep-dive/projects/${encodeURIComponent(projectId)}`), { method: 'DELETE' })
  if (isUnsupported(response)) return deleteLocalProject(projectId)
  return parseApiResponse(response, '项目删除失败')
}

export async function addProjectMaterial(projectId, payload) {
  const response = await fetch(apiUrl(`/project-deep-dive/projects/${encodeURIComponent(projectId)}/materials`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (isUnsupported(response)) return addLocalMaterial(projectId, payload)
  return parseApiResponse(response, '项目材料上传失败')
}

export async function generateProjectQuestions(projectId, payload) {
  const response = await fetch(apiUrl(`/project-deep-dive/projects/${encodeURIComponent(projectId)}/generate`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (isUnsupported(response)) throw new Error('项目深挖问题生成服务不可用，请确认后端服务已启动')
  return parseApiResponse(response, '项目深挖问题生成失败')
}

function isUnsupported(response) {
  return response.status === 404 || response.status === 405
}

function loadLocalProjects() {
  try {
    const rows = JSON.parse(localStorage.getItem(LOCAL_KEY) || '[]')
    return Array.isArray(rows) ? rows : []
  } catch (_) {
    return []
  }
}

function saveLocalProjects(rows) {
  localStorage.setItem(LOCAL_KEY, JSON.stringify(rows || []))
}

function createLocalProject(payload = {}) {
  const now = new Date().toISOString()
  const project = {
    projectId: `pdd_${randomId()}`,
    name: String(payload.name || '').trim() || '未命名项目',
    role: String(payload.role || '').trim() || '核心开发',
    techStack: String(payload.techStack || '').trim(),
    summary: String(payload.summary || '').trim(),
    materials: [],
    questions: [],
    createdAt: now,
    updatedAt: now,
  }
  const rows = loadLocalProjects()
  rows.unshift(project)
  saveLocalProjects(rows)
  return project
}

function deleteLocalProject(projectId) {
  saveLocalProjects(loadLocalProjects().filter(item => item.projectId !== projectId))
  return { projectId }
}

function addLocalMaterial(projectId, payload = {}) {
  const rows = loadLocalProjects()
  const project = rows.find(item => item.projectId === projectId)
  if (!project) throw new Error('项目不存在')
  const content = String(payload.content || '')
  const material = {
    materialId: `mat_${randomId()}`,
    projectId,
    fileName: String(payload.fileName || '项目材料.txt'),
    contentType: String(payload.contentType || 'text/plain'),
    content,
    preview: content.replace(/\s+/g, ' ').slice(0, 160),
    createdAt: new Date().toISOString(),
  }
  project.materials = [material, ...(project.materials || [])]
  project.updatedAt = new Date().toISOString()
  saveLocalProjects(rows)
  return project
}

function randomId() {
  return Math.random().toString(16).slice(2, 10) + Date.now().toString(16).slice(-8)
}
