import { apiFetch, apiUrl, parseApiResponse } from './http'

export async function listDeepDiveProjects() {
  const response = await apiFetch('/project-deep-dive/projects')
  return (await parseApiResponse(response, '项目深挖列表加载失败')) || []
}

export async function getDeepDiveProject(projectId) {
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}`)
  return parseApiResponse(response, '项目详情加载失败')
}

export async function createDeepDiveProject(payload) {
  const response = await apiFetch('/project-deep-dive/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '项目保存失败')
}

export async function updateDeepDiveProject(projectId, payload) {
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '项目保存失败')
}

export async function deleteDeepDiveProject(projectId) {
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' })
  return parseApiResponse(response, '项目删除失败')
}

export async function addProjectMaterial(projectId, file) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}/materials`, {
    method: 'POST',
    body: formData,
  })
  return parseApiResponse(response, '项目文件上传失败')
}

export function projectMaterialFileUrl(materialId) {
  return apiUrl(`/project-deep-dive/materials/${encodeURIComponent(materialId)}/file`)
}

export function projectMaterialBatchDownloadUrl(materialIds) {
  const query = (materialIds || [])
    .filter(Boolean)
    .map((materialId) => `materialIds=${encodeURIComponent(materialId)}`)
    .join('&')
  return query ? apiUrl(`/project-deep-dive/materials/batch-file?${query}`) : '#'
}

export async function deleteProjectMaterial(materialId) {
  const response = await apiFetch(`/project-deep-dive/materials/${encodeURIComponent(materialId)}`, {
    method: 'DELETE',
  })
  return parseApiResponse(response, '材料删除失败')
}

export async function addProjectQuestion(projectId, payload) {
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}/questions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '问题新增失败')
}

export async function updateProjectQuestion(questionId, payload) {
  const response = await apiFetch(`/project-deep-dive/questions/${encodeURIComponent(questionId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '问题保存失败')
}

export async function deleteProjectQuestion(questionId) {
  const response = await apiFetch(`/project-deep-dive/questions/${encodeURIComponent(questionId)}`, {
    method: 'DELETE',
  })
  return parseApiResponse(response, '问题删除失败')
}

export async function generateProjectQuestions(projectId, payload) {
  const response = await apiFetch(`/project-deep-dive/projects/${encodeURIComponent(projectId)}/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '项目深挖问题生成失败')
}
