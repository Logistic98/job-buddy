import { apiUrl, parseApiResponse } from './http'

export async function listQuestions(params = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.bankType) query.set('bankType', params.bankType)
  if (params.category) query.set('category', params.category)
  if (params.difficulty) query.set('difficulty', params.difficulty)
  if (params.page) query.set('page', params.page)
  if (params.size) query.set('size', params.size)
  if (params._ts) query.set('_ts', params._ts)
  const url = apiUrl(`/interview/questions${query.toString() ? `?${query}` : ''}`)
  const data = await fetchWithStartupRetry(url, '笔试题库加载失败')
  return Array.isArray(data) ? { items: data, total: data.length, page: 1, size: data.length || 20, pages: 1 } : data
}

export async function getQuestionMeta(params = {}) {
  const query = new URLSearchParams()
  if (params.bankType) query.set('bankType', params.bankType)
  if (params._ts) query.set('_ts', params._ts)
  const response = await fetch(apiUrl(`/interview/questions/meta${query.toString() ? `?${query}` : ''}`), { cache: 'no-store' })
  return parseApiResponse(response, '题库元数据加载失败')
}

async function fetchWithStartupRetry(url, fallbackMessage, attempts = 3) {
  let lastError = null
  for (let i = 0; i < attempts; i++) {
    try {
      const response = await fetch(url, { cache: 'no-store', headers: { 'Cache-Control': 'no-cache' } })
      return await parseApiResponse(response, fallbackMessage)
    } catch (error) {
      lastError = error
      const message = String(error?.message || '')
      const retryable = message.includes('HTTP 500') && message.includes('响应体为空')
      if (!retryable || i === attempts - 1) break
      await new Promise(resolve => setTimeout(resolve, 800 * (i + 1)))
    }
  }
  throw lastError
}

export async function createQuestion(payload) {
  const response = await fetch(apiUrl('/interview/questions'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '笔试题保存失败')
}

export async function generateQuestions(payload) {
  const response = await fetch(apiUrl('/interview/questions/generate'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (isUnsupported(response)) {
    const items = buildGeneratedQuestions(payload)
    const result = await saveQuestionsOneByOne(items)
    return { ...result, fallback: true }
  }
  return parseApiResponse(response, 'AI 生成题目失败')
}

function isUnsupported(response) {
  return response.status === 404 || response.status === 405
}

async function saveQuestionsOneByOne(items = []) {
  const rows = Array.isArray(items) ? items : [items]
  const saved = []
  for (const item of rows) {
    if (!item || typeof item !== 'object') continue
    saved.push(await createQuestion(item))
  }
  return { count: saved.length, items: saved }
}

function buildGeneratedQuestions(payload = {}) {
  const topic = payload.topic || 'Java 后端'
  const category = payload.category || topic
  const difficulty = payload.difficulty || '中等'
  const bankType = payload.bankType || (payload.questionType === '编程题' ? 'leetcode' : 'baguwen')
  const questionType = bankType === 'leetcode' ? '编程题' : (payload.questionType || '单选')
  const requirements = payload.requirements || '结合工程实践、原理理解和排障经验'
  const documentText = String(payload.documentText || '').replace(/\s+/g, ' ').slice(0, 600)
  const sourceHint = documentText ? `参考资料：${documentText}` : ''
  const count = Math.max(1, Math.min(Number(payload.count || 5), 20))
  const templates = [
    index => ({ title: `${topic} 核心机制 ${index}`, stem: `请说明 ${topic} 中一个核心机制的工作原理，并结合实际项目解释它解决了什么问题。要求：${requirements} ${sourceHint}` }),
    index => ({ title: `${topic} 性能排障 ${index}`, stem: `如果线上 ${topic} 相关能力出现性能下降，你会如何定位、验证和修复？请给出排查步骤。要求：${requirements} ${sourceHint}` }),
    index => ({ title: `${topic} 系统设计 ${index}`, stem: `请设计一个与 ${topic} 相关的工程方案，说明模块划分、关键数据流、异常处理和可观测性设计。要求：${requirements} ${sourceHint}` }),
    index => ({ title: `${topic} 方案对比 ${index}`, stem: `请比较 ${topic} 中两个常见方案的优缺点、适用场景和风险边界。要求：${requirements} ${sourceHint}` }),
    index => ({ title: `${topic} 项目实践 ${index}`, stem: `请结合一次真实或模拟项目经历，说明你如何落地 ${topic}，包括技术选择、难点和结果指标。要求：${requirements} ${sourceHint}` }),
  ]
  return Array.from({ length: count }, (_, idx) => {
    const base = templates[idx % templates.length](idx + 1)
    return {
      title: base.title,
      bankType,
      content: buildQuestionContent(base.stem, questionType),
      category,
      difficulty,
      questionType,
      tags: [category, topic].filter(Boolean),
      answer: ['单选', '多选'].includes(questionType) ? (questionType === '多选' ? 'A,C' : 'A') : `参考答案应覆盖：核心概念和原理、关键流程或架构设计、异常与性能边界、结合 ${topic} 的项目案例、权衡理由和可验证结果。补充要求：${requirements}`,
    }
  })
}

function buildQuestionContent(stem, questionType) {
  if (questionType === '编程题') return `${stem.trim()}\n\n请任选 Python、Java 或 JavaScript 完成解法，并覆盖示例与边界场景。`
  if (!['单选', '多选'].includes(questionType)) return stem.trim()
  return `${stem.trim()}\n\nA. 原理清晰、能结合工程场景并说明边界\nB. 只背诵概念，不说明适用场景\nC. 能给出排查步骤、验证指标和回滚方案\nD. 完全忽略异常处理和性能影响`
}

export async function updateQuestion(questionId, payload) {
  const response = await fetch(apiUrl(`/interview/questions/${encodeURIComponent(questionId)}`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-cache' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '笔试题更新失败')
}

export async function deleteQuestion(questionId) {
  const id = encodeURIComponent(questionId)
  const response = await fetch(apiUrl(`/interview/questions/${id}`), { method: 'DELETE' })
  if (isUnsupported(response)) {
    const fallback = await fetch(apiUrl(`/interview/questions/${id}/delete`), { method: 'POST' })
    return parseApiResponse(fallback, '笔试题删除失败')
  }
  return parseApiResponse(response, '笔试题删除失败')
}

export async function batchQuestions(payload) {
  const response = await fetch(apiUrl('/interview/questions/batch'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (isUnsupported(response) && payload?.action === 'delete') {
    const ids = Array.isArray(payload.questionIds) ? payload.questionIds : []
    for (const id of ids) await deleteQuestion(id)
    return { action: 'delete', count: ids.length, fallback: true }
  }
  return parseApiResponse(response, '笔试题批量操作失败')
}

export async function listExams() {
  const response = await fetch(apiUrl('/interview/practices'))
  return (await parseApiResponse(response, '练习记录加载失败')) || []
}

export async function getExam(examId) {
  const response = await fetch(apiUrl(`/interview/practices/${encodeURIComponent(examId)}`))
  return parseApiResponse(response, '练习详情加载失败')
}

export async function createRandomExam(payload) {
  const response = await fetch(apiUrl('/interview/practices/random'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '随机出题失败')
}

export async function runCodeSample(payload) {
  const response = await fetch(apiUrl('/interview/code/run'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  return parseApiResponse(response, '代码运行失败')
}

export async function submitExam(examId, answers, codingResults = {}) {
  const response = await fetch(apiUrl(`/interview/practices/${encodeURIComponent(examId)}/submit`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answers, codingResults }),
  })
  return parseApiResponse(response, '练习提交失败')
}
