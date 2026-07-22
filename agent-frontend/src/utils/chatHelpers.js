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

export function normalizeToolEvent(item = {}) {
  return {
    ...item,
    name: item.name || item.title || item.id,
    detail: item.summary || (typeof item.detail === 'string' ? item.detail : ''),
    payload: item.payload !== undefined ? item.payload : item.detail,
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
