export function normalizeJobDescriptionText(value) {
  return String(value || '')
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(line => line.replace(/[\t\u00a0]+/g, ' ').replace(/ {2,}/g, ' ').trim())
    .filter(Boolean)
    .join('\n')
}

export function compactJobSummaryText(value, maxLength = 180) {
  const text = normalizeJobDescriptionText(value).replace(/\n+/g, ' ')
  return maxLength > 0 && text.length > maxLength ? text.slice(0, maxLength).trimEnd() : text
}

export function firstJobDescriptionText(item = {}) {
  return item.jobDescription || item.description || item.postDescription || item.jobDesc || item.jobSecText || item.detailText || item.jobRequire || ''
}
