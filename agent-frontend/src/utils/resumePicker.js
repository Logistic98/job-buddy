function textValue(value) {
  if (Array.isArray(value)) return value.map(textValue).filter(Boolean).join('、')
  if (value && typeof value === 'object')
    return textValue(value.name || value.title || value.label || value.description)
  return String(value || '').trim()
}

function firstText(parsed, keys) {
  for (const key of keys) {
    const value = textValue(parsed?.[key])
    if (value) return value
  }
  return ''
}

function truncate(value, maxLength = 120) {
  const text = textValue(value).replace(/\s+/g, ' ')
  return text.length > maxLength ? `${text.slice(0, maxLength).trim()}…` : text
}

export function resumePickerTitle(item) {
  return textValue(item?.originalName) || textValue(item?.resumeId) || '未命名简历'
}

export function resumePickerSkills(item) {
  const parsed = item?.parsed || {}
  const raw = parsed.skills || parsed.skill_tags || parsed.skillTags || []
  const rows = Array.isArray(raw) ? raw : String(raw || '').split(/[,，、\s]+/)
  return Array.from(new Set(rows.map(textValue).filter(Boolean)))
}

export function resumePickerSummary(item, loading = false) {
  if (loading) return '正在加载简历摘要…'
  const parsed = item?.parsed || {}
  const explicit = firstText(parsed, [
    'summary',
    'personal_advantage',
    'personalAdvantage',
    'professional_summary',
    'professionalSummary',
    'self_evaluation',
    'selfEvaluation',
    'advantage',
    'intro',
    'description',
  ])
  if (explicit) return truncate(explicit)

  const years = firstText(parsed, ['years_experience', 'yearsExperience', 'work_years', 'workYears'])
  const direction = firstText(parsed, [
    'current_title',
    'currentTitle',
    'expected_titles',
    'expectedTitles',
    'job_intentions',
    'jobIntentions',
  ])
  const skills = resumePickerSkills(item).slice(0, 6)
  const parts = []
  if (years) parts.push(`${years}工作经验`)
  if (direction) parts.push(`求职方向：${direction}`)
  if (skills.length) parts.push(`核心技能：${skills.join('、')}`)
  if (parts.length) return truncate(parts.join('；'))

  const workRows = parsed.work_experiences || parsed.workExperiences || parsed.experiences || []
  const projectRows = parsed.project_experiences || parsed.projectExperiences || parsed.projects || []
  const detail = [...(Array.isArray(workRows) ? workRows : []), ...(Array.isArray(projectRows) ? projectRows : [])]
    .map((row) => firstText(row, ['description', 'content', 'detail', 'responsibility', 'achievement']))
    .find(Boolean)
  if (detail) return truncate(detail)

  return item?.parseStatus === 'success'
    ? '当前文件缺少可展示摘要，可选择后重新分析生成。'
    : '简历摘要尚未生成，可选择后进行解析和分析。'
}

export function mergeResumePickerDetail(item, details) {
  const detail = item?.resumeId ? details?.[item.resumeId] : null
  if (!detail) return item
  return { ...item, ...detail, parsed: { ...(item?.parsed || {}), ...(detail?.parsed || {}) } }
}

export function resumePickerSearchText(item) {
  const parsed = item?.parsed || {}
  return [
    resumePickerTitle(item),
    resumePickerSummary(item),
    ...resumePickerSkills(item),
    parsed.expected_titles,
    parsed.expectedTitles,
    parsed.job_intentions,
    parsed.jobIntentions,
  ]
    .map(textValue)
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
}
