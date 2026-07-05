export function firstUsableJobPathId(item) {
  for (const key of ['encryptJobId', 'encrypt_job_id', 'jobId', 'job_id', 'id']) {
    const value = String(item[key] || '').trim()
    if (value && !/^\d{4,}$/.test(value)) return value
  }
  return ''
}

export function bossDetailUrl(item) {
  const pathId = firstUsableJobPathId(item)
  if (!pathId) return ''
  const params = new URLSearchParams()
  const securityId = item.securityId || item.security_id || ''
  const lid = item.lid || item.listId || ''
  if (securityId) params.set('securityId', securityId)
  if (lid) params.set('lid', lid)
  const query = params.toString()
  return `https://www.zhipin.com/job_detail/${encodeURIComponent(pathId)}.html${query ? `?${query}` : ''}`
}
