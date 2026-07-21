const TYPE_LABELS = { company: '公司', keyword: '关键词' }

export function normalizeBlacklistName(name) {
  return String(name || '')
    .trim()
    .toLowerCase()
    .replace(/[\s　]+/g, '')
}

export function findBlacklistDuplicate(items, type, name) {
  const target = normalizeBlacklistName(name)
  if (!target) return null
  return (items || []).find((item) => item?.type === type && normalizeBlacklistName(item?.name) === target) || null
}

export function createBlacklistItem(type, name, id) {
  return {
    id: id || `blk_manual_${Date.now()}`,
    name: String(name || '').trim(),
    type,
    enabled: true,
    source: 'manual',
    reason: type === 'keyword' ? '手动关键词屏蔽' : '手动公司屏蔽',
  }
}

export function filterBlacklistItems(items, filter) {
  const scope = filter?.scope || 'all'
  const keyword = String(filter?.keyword || '')
    .trim()
    .toLowerCase()
  return (items || []).filter((item) => {
    if (scope === 'company' && item?.type !== 'company') return false
    if (scope === 'keyword' && item?.type !== 'keyword') return false
    if (scope === 'disabled' && item?.enabled) return false
    if (!keyword) return true
    const name = String(item?.name || '').toLowerCase()
    const reason = String(item?.reason || '').toLowerCase()
    return name.includes(keyword) || reason.includes(keyword)
  })
}

export function buildBlacklistScopes(items) {
  const rows = items || []
  return [
    { key: 'all', label: '全部', count: rows.length },
    { key: 'company', label: TYPE_LABELS.company, count: rows.filter((item) => item?.type === 'company').length },
    { key: 'keyword', label: TYPE_LABELS.keyword, count: rows.filter((item) => item?.type === 'keyword').length },
    { key: 'disabled', label: '已停用', count: rows.filter((item) => !item?.enabled).length },
  ]
}
