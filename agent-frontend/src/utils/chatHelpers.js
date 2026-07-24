// Pure, side-effect-free helpers shared by the chat store. Extracted from the store so the
// logic can be unit-tested in isolation and the store file stays focused on state and effects.

export function normalizeMessageText(message) {
  return String(message || '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function requestKey(sessionId, resumeId, message, selectedJob = null) {
  const selectedJobKey = selectedJob
    ? String(
        selectedJob.favoriteKey ||
          selectedJob.securityId ||
          selectedJob.id ||
          selectedJob.jobId ||
          selectedJob.encryptJobId ||
          '',
      )
    : ''
  return `${sessionId || 'new'}::${resumeId || 'none'}::${normalizeMessageText(message)}::${selectedJobKey}`
}

export function isAbortError(error) {
  return error?.name === 'AbortError' || String(error?.message || '').includes('aborted')
}

export function extractErrorMessage(error, fallback = '请求失败，请稍后重试。', depth = 0) {
  if (error === null || error === undefined || depth > 4) return fallback
  if (typeof error === 'string' || typeof error === 'number') {
    const text = String(error).trim()
    return text && text !== '[object Object]' ? text : fallback
  }
  if (typeof error === 'object') {
    for (const key of ['message', 'detail', 'summary', 'error', 'reason']) {
      const value = error[key]
      if (value !== null && value !== undefined && value !== error) {
        const message = extractErrorMessage(value, '', depth + 1)
        if (message) return message
      }
    }
    if (error.code !== null && error.code !== undefined && String(error.code).trim()) {
      return `请求处理失败（错误码：${String(error.code).trim()}）`
    }
    return fallback
  }
  const text = String(error).trim()
  return text && text !== '[object Object]' ? text : fallback
}

export function formatSendError(error) {
  const raw = extractErrorMessage(error)
  if (raw.includes('Failed to fetch') || raw.includes('NetworkError') || raw.includes('Load failed')) {
    return '服务暂时不可用，请确认后端服务已完全启动后再重试。'
  }
  return raw
}

export function isBossAuthenticated(status) {
  if (!status) return false
  const data = status.data && typeof status.data === 'object' ? status.data : status
  return Boolean(
    status.ok === true ||
    status.authenticated === true ||
    status.search_authenticated === true ||
    status.status === 'logged_in' ||
    data.authenticated === true ||
    data.search_authenticated === true ||
    data.status === 'logged_in',
  )
}

export function activeToolSummary(item = {}) {
  const detail = String(item?.detail || '').trim()
  return detail || '请求已提交，正在初始化会话和服务链路，请稍候。'
}

export function normalizeToolEvent(item = {}) {
  const payload = item.payload !== undefined ? item.payload : item.detail
  const candidateCount =
    item.id === 'job_search' && payload && typeof payload === 'object'
      ? firstValue([payload], ['candidateCount', 'count', 'total', 'jobCount'])
      : null
  const summary =
    item.id === 'job_search' && item.status === 'success' && candidateCount !== null
      ? `累计检索到 ${candidateCount} 个候选岗位。`
      : item.summary
  return {
    ...item,
    name: item.name || item.title || item.id,
    detail: summary || (typeof item.detail === 'string' ? item.detail : ''),
    payload,
  }
}

export function isMemoryNoiseEvent(item = {}) {
  // 只按稳定标识字段 id/name 判定记忆读取类噪声，不匹配 title/summary 等展示文案，
  // 否则用户问题或步骤摘要里出现“记忆/memory”字样时整条推理步骤会被误删。
  const text = [item.id, item.name].map((value) => String(value || '').toLowerCase()).join(' ')
  return text.includes('memory') || text.includes('记忆')
}

export function filterVisibleToolEvents(events = []) {
  return (Array.isArray(events) ? events : [])
    .filter((item) => item?.id !== 'sse_connect')
    .filter((item) => !isMemoryNoiseEvent(item))
    .map((item) => normalizeToolEvent(item))
}

const protectedMarkdownPattern =
  /(```[\s\S]*?```|~~~[\s\S]*?~~~|`[^`\n]*`|!?\[[^\]\n]*\]\([^)\n]+\)|https?:\/\/[^\s<>"'，。；、！？（）()\[\]{}【】《》\u4e00-\u9fff]+)/g

/**
 * 修复助手普通文本中的重复标点，同时保护代码、Markdown 链接和 URL。
 * 这里只处理明确的重复句末符号，不把省略号、版本号或技术符号改写成自然语言。
 */
export function normalizeAssistantMarkdown(content) {
  const normalized = String(content || '')
    .split(protectedMarkdownPattern)
    .map((part, index) => (index % 2 === 1 ? part : normalizeProsePunctuation(part)))
    .join('')
  return linkifyBareUrls(normalized)
}

function normalizeProsePunctuation(content) {
  return String(content || '')
    .replace(/。{2,}/g, '。')
    .replace(/！{2,}/g, '！')
    .replace(/？{2,}/g, '？')
    .replace(/；{2,}/g, '；')
    .replace(/，{2,}/g, '，')
    .replace(/。([，；：])/g, '$1')
    .replace(/；。/g, '。')
    .replace(/，。/g, '。')
    .replace(/\.+([。！？])/g, '$1')
    .replace(/([。！？])\.+/g, '$1')
}

export function linkifyBareUrls(content) {
  const urlPattern = /https?:\/\/[^\s<>"'，。；、！？（）()\[\]{}【】《》\u4e00-\u9fff]+/g
  return String(content || '').replace(urlPattern, (raw, offset, source) => {
    const before = source.slice(Math.max(0, offset - 3), offset)
    if (before.endsWith('](') || before.endsWith(']（') || before.endsWith('<')) return raw
    const trailing = raw.match(/[.,;:!?]+$/)?.[0] || ''
    const url = trailing ? raw.slice(0, -trailing.length) : raw
    if (!url) return raw
    return `[${url}](${url})${trailing}`
  })
}

const intentLabels = {
  'resume.match': '简历与岗位匹配',
  'resume.analyze': '简历解析',
  'job.recommend': '岗位推荐',
  'job.analyze': '岗位分析',
  'general.chat': '通用智能问答',
}

const actionLabels = {
  call_match_resume: '执行简历匹配',
  run_resume_match: '执行简历匹配',
  call_match_resume_sections: '执行简历匹配',
  call_get_recommend_jobs: '检索候选岗位',
  run_job_recommend: '检索候选岗位',
  runtime_managed: '生成智能回答',
  clarify: '补充必要信息',
}

function firstValue(objects, keys) {
  for (const object of objects) {
    if (!object || typeof object !== 'object') continue
    for (const key of keys) {
      const value = object[key]
      if (value && typeof value === 'object' && !Array.isArray(value)) continue
      if (value !== undefined && value !== null && value !== '') return value
    }
  }
  return null
}

function displayConfidence(value) {
  if (value === null || value === undefined || value === '') return ''
  const numeric = Number(value)
  if (Number.isFinite(numeric) && numeric >= 0 && numeric <= 1) return `${Math.round(numeric * 100)}%`
  const labels = { high: '高', medium: '中', low: '低' }
  return labels[String(value).toLowerCase()] || String(value)
}

function conciseScalar(value) {
  if (value === null || value === undefined) return ''
  if (Array.isArray(value)) return conciseScalar(value.find((item) => typeof item === 'string'))
  if (typeof value === 'object') return ''
  const text = String(value).replace(/\s+/g, ' ').trim()
  // 长正文通常是完整 JD、简历或内部响应，不属于过程面板应展示的“关键详情”。
  return text.length <= 180 ? text : ''
}

/** 从工具 payload 中按白名单精选少量可读字段，绝不回显原始 JSON。 */
export function selectToolEventHighlights(item = {}) {
  const payload = item?.payload && typeof item.payload === 'object' ? item.payload : {}
  const directive = payload.directive && typeof payload.directive === 'object' ? payload.directive : {}
  const intent = payload.intent && typeof payload.intent === 'object' ? payload.intent : {}
  const top = payload.top && typeof payload.top === 'object' ? payload.top : {}
  const selectedJob = payload.selectedJob && typeof payload.selectedJob === 'object' ? payload.selectedJob : {}
  const slots = {
    ...(directive.slots && typeof directive.slots === 'object' ? directive.slots : {}),
    ...(payload.slots && typeof payload.slots === 'object' ? payload.slots : {}),
  }
  const sources = [payload, directive, intent, top, selectedJob, slots]
  const highlights = []
  const seenLabels = new Set()
  const add = (label, rawValue) => {
    const value = conciseScalar(rawValue)
    if (!value || seenLabels.has(label) || highlights.length >= 4) return
    seenLabels.add(label)
    highlights.push({ label, value })
  }

  if (item.id === 'runtime_understanding' || item.id === 'runtime_managed') {
    const intentName = firstValue(sources, ['intent'])
    const domain = firstValue(sources, ['domain'])
    add('任务类型', intentLabels[intentName] || [domain, intentName].filter(Boolean).join(' / '))
    add('置信度', displayConfidence(firstValue(sources, ['confidence', 'score_confidence'])))
    const action = firstValue(sources, ['next_action', 'nextAction'])
    add('下一步', actionLabels[action] || action)
  }

  if (item.id === 'resume_match') {
    const score = firstValue([top, payload], ['score'])
    add('匹配评分', score === null ? '' : `${score}/100`)
    add('投递建议', firstValue([top, payload], ['recommendation']))
    add('置信度', displayConfidence(firstValue([top, payload], ['score_confidence', 'confidence'])))
    add('关键依据', firstValue([top], ['hits', 'evidence']))
    add('主要差距', firstValue([top], ['gaps', 'limitations']))
  }

  if (item.id === 'recommendation_quality_gate') {
    const candidateCount = firstValue(sources, ['candidateCount'])
    const qualifiedCount = firstValue(sources, ['qualifiedCount'])
    const minimumScore = firstValue(sources, ['minimumScore'])
    add('候选岗位', candidateCount === null ? '' : `${candidateCount} 个`)
    add('通过门槛', qualifiedCount === null ? '' : `${qualifiedCount} 个`)
    add('最低匹配分', minimumScore === null ? '' : `${minimumScore} 分`)
    const rejectionReasons = payload.rejectionReasons
    if (rejectionReasons && typeof rejectionReasons === 'object' && !Array.isArray(rejectionReasons)) {
      add(
        '主要剔除原因',
        Object.entries(rejectionReasons)
          .slice(0, 3)
          .map(([reason, count]) => `${reason} ${count} 个`)
          .join('；'),
      )
    }
  }

  if (item.id === 'job_search' || item.id === 'job_flip') {
    const count = firstValue(sources, ['candidateCount', 'count', 'total', 'jobCount'])
    add('候选岗位', count === null ? '' : `${count} 个`)
    add('目标方向', firstValue(sources, ['target_role', 'targetRole', 'keyword', 'query']))
    add('城市', firstValue(sources, ['city', 'location']))
    const salaryMin = firstValue(sources, ['salary_min_k', 'salaryMinK'])
    const salaryMax = firstValue(sources, ['salary_max_k', 'salaryMaxK'])
    add('薪资范围', salaryMin && salaryMax ? `${salaryMin}-${salaryMax}K` : '')
  }

  if (item.id === 'selected_job_context') {
    const jobName = firstValue([selectedJob, payload], ['jobName', 'job_name', 'title'])
    const company = firstValue([selectedJob, payload], ['brandName', 'companyName', 'company'])
    add('目标岗位', [company, jobName].filter(Boolean).join(' / '))
    add('证据状态', firstValue(sources, ['evidenceStatus', 'status', 'source']))
  }

  add('目标方向', firstValue(sources, ['target_role', 'targetRole']))
  add('城市', firstValue(sources, ['city', 'location']))
  add('处理结果', firstValue(sources, ['stopReason', 'stop_reason', 'status']))
  add('异常说明', firstValue(sources, ['warning', 'error']))
  return highlights
}

/**
 * 从已有 reasoning 中选取目标、依据、风险和下一步等高信号句子。
 * 返回完整句子而非字符截断，且限制条数与总量，避免把完整内部推理直接铺到界面。
 */
export function selectReasoningHighlights(reasoning, limit = 4) {
  const normalized = normalizeProsePunctuation(String(reasoning || ''))
  const candidates = normalized
    .split(/\n+|(?<=[。！？!?])\s*/)
    .map((text) => text.replace(/^\s*(?:[-*+] |\d+[.)、]\s*)/, '').trim())
    .filter((text) => text && text.length <= 220 && !/^[{[]/.test(text))
  const unique = []
  const seen = new Set()
  candidates.forEach((text, index) => {
    const key = text.replace(/[\s，。！？；:：]/g, '').toLowerCase()
    if (!key || seen.has(key)) return
    seen.add(key)
    let score = 0
    if (/(结论|优势|差距|短板|风险|建议|下一步|证据|因此|综合|值得|推荐|不建议)/.test(text)) score += 4
    if (/(判断|依据|匹配|限制|核心|关键|原因)/.test(text)) score += 2
    if (/(用户|任务|指令|要求包括|输出要求|需要先|我需要)/.test(text)) score -= 3
    if (text.length > 160) score -= 1
    unique.push({ text, index, score })
  })
  return unique
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .slice(0, Math.max(1, limit))
    .sort((left, right) => left.index - right.index)
    .map((item) => item.text)
}
