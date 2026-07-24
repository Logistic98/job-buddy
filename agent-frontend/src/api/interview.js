import { apiFetch, parseApiResponse } from './http'

export async function listQuestions(params = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.bankType) query.set('bankType', params.bankType)
  if (params.category) query.set('category', params.category)
  if (params.difficulty) query.set('difficulty', params.difficulty)
  if (params.page) query.set('page', params.page)
  if (params.size) query.set('size', params.size)
  if (params._ts) query.set('_ts', params._ts)
  const path = `/interview/questions${query.toString() ? `?${query}` : ''}`
  const data = await fetchWithStartupRetry(path, '笔试题库加载失败')
  return Array.isArray(data) ? { items: data, total: data.length, page: 1, size: data.length || 20, pages: 1 } : data
}

export async function getQuestionMeta(params = {}) {
  const query = new URLSearchParams()
  if (params.bankType) query.set('bankType', params.bankType)
  if (params._ts) query.set('_ts', params._ts)
  const response = await apiFetch(`/interview/questions/meta${query.toString() ? `?${query}` : ''}`, {
    cache: 'no-store',
  })
  return parseApiResponse(response, '题库元数据加载失败')
}

async function fetchWithStartupRetry(path, fallbackMessage, attempts = 3) {
  let lastError = null
  for (let i = 0; i < attempts; i++) {
    try {
      const response = await apiFetch(path, { cache: 'no-store', headers: { 'Cache-Control': 'no-cache' } })
      return await parseApiResponse(response, fallbackMessage)
    } catch (error) {
      lastError = error
      const message = String(error?.message || '')
      const retryable = message.includes('HTTP 500') && message.includes('响应体为空')
      if (!retryable || i === attempts - 1) break
      await new Promise((resolve) => setTimeout(resolve, 800 * (i + 1)))
    }
  }
  throw lastError
}

export async function createQuestion(payload) {
  const response = await apiFetch('/interview/questions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '笔试题保存失败')
}

export async function extractInterviewDocument(file) {
  const body = new FormData()
  body.append('file', file)
  const response = await apiFetch('/interview/documents/extract', {
    method: 'POST',
    body,
  })
  return parseApiResponse(response, '参考资料读取失败')
}

export async function generateQuestions(payload) {
  const response = await apiFetch('/interview/questions/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, 'AI 生成题目失败')
}

export async function importQuestions(payload) {
  const response = await apiFetch('/interview/questions/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '候选题导入失败')
}

export async function updateQuestion(questionId, payload) {
  const response = await apiFetch(`/interview/questions/${encodeURIComponent(questionId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '笔试题更新失败')
}

export async function deleteQuestion(questionId) {
  const id = encodeURIComponent(questionId)
  const response = await apiFetch(`/interview/questions/${id}`, { method: 'DELETE' })
  return parseApiResponse(response, '笔试题删除失败')
}

export async function batchQuestions(payload) {
  const response = await apiFetch('/interview/questions/batch', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '笔试题批量操作失败')
}

export async function listExams() {
  const response = await apiFetch('/interview/practices')
  return (await parseApiResponse(response, '练习记录加载失败')) || []
}

export async function getExam(examId) {
  const response = await apiFetch(`/interview/practices/${encodeURIComponent(examId)}`)
  return parseApiResponse(response, '练习详情加载失败')
}

export async function createRandomExam(payload) {
  const response = await apiFetch('/interview/practices/random', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '随机出题失败')
}

export async function runCodeSample(payload) {
  const response = await apiFetch('/interview/code/run', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '代码运行失败')
}

export async function submitExam(examId, answers, codingResults = {}) {
  const response = await apiFetch(`/interview/practices/${encodeURIComponent(examId)}/submit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answers, codingResults }),
  })
  return parseApiResponse(response, '练习提交失败')
}
