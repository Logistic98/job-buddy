// Pure, side-effect-free helpers for the interview question bank. Extracted from InterviewBank.vue
// so tag parsing, coding-template generation and formatting can be unit-tested in isolation while
// the component keeps only its reactive state and API orchestration.

export const codingLanguageOptions = [
  { value: 'python', label: 'Python' },
  { value: 'java', label: 'Java' },
  { value: 'javascript', label: 'JavaScript' },
]

export function codingMeta(item) {
  return item?.codingMeta && typeof item.codingMeta === 'object' ? item.codingMeta : {}
}

export function cleanTagText(value) {
  let text = String(value || '').trim()
  const labelMatch = text.match(/(?:^|[\{,\s])label\s*[:=]\s*([^,}\]]+)/i)
  if (labelMatch) text = labelMatch[1].trim()
  text = text
    .replace(/^[\{\[\(]+|[\}\]\)]+$/g, '')
    .replace(/^label\s*[:=]\s*/i, '')
    .replace(/^['"]|['"]$/g, '')
    .trim()
  return text.toLowerCase() === 'leetcode' ? '算法' : text
}

export function normalizeTagLabel(tag) {
  if (!tag) return ''
  if (typeof tag === 'object') return cleanTagText(tag.label || tag.name || tag.value || '')
  return cleanTagText(tag)
}

export function tagLabels(item) {
  const raw = item.tags || []
  const rows = Array.isArray(raw) ? raw : String(raw).split(/[,，、\n\r\t]+/)
  return Array.from(new Set(rows.map(normalizeTagLabel).filter(Boolean)))
}

export function splitCleanTags(value) {
  return Array.from(new Set(String(value || '').split(/[,，、\s]+/).map(cleanTagText).filter(Boolean)))
}

export function defaultOptions() { return ['A', 'B', 'C', 'D'].map(key => ({ key, text: '' })) }

export function isChoiceType(type) { return ['单选', '多选'].includes(type) }

export function isMultiChoice(item) { return item.questionType === '多选' }

export function isCodingQuestion(item) { return item?.bankType === 'leetcode' || item?.questionType === '编程题' }

export function questionStem(item) {
  return String(item.content || '').replace(/\n\n?[A-Z][.、]\s+[^\n]+(?:\n[A-Z][.、]\s+[^\n]+)+\s*$/m, '').trim() || item.content || ''
}

export function optionItems(item) {
  const text = String(item.content || '')
  const rows = text.match(/(?:^|\n)([A-Z])[.、]\s+([^\n]+)/g) || []
  return rows.map(row => {
    const match = row.trim().match(/^([A-Z])[.、]\s+(.+)$/)
    return match ? { key: match[1], text: match[2] } : null
  }).filter(Boolean)
}

export function normalizeCodingLanguage(value) {
  const text = String(value || '').trim().toLowerCase()
  if (['js', 'javascript', 'node'].includes(text)) return 'javascript'
  if (['py', 'python', 'python3'].includes(text)) return 'python'
  if (text === 'java') return 'java'
  return 'python'
}

export function formatCodingTests(tests) {
  try { return tests ? JSON.stringify(tests, null, 2) : '' } catch (_) { return '' }
}

export function buildDefaultTemplate(functionName, language = 'python') {
  const name = functionName || 'solution'
  const lang = normalizeCodingLanguage(language)
  if (lang === 'java') return `class Solution {\n    public Object ${name}(Object... args) {\n        // TODO\n        return null;\n    }\n}`
  if (lang === 'javascript') return `function ${name}() {\n  // TODO\n}`
  return `def ${name}(*args):\n    # TODO\n    pass\n`
}

export function defaultSignature(functionName, language = 'python') {
  const name = functionName || 'solution'
  const lang = normalizeCodingLanguage(language)
  if (lang === 'java') return `class Solution { public Object ${name}(Object... args) }`
  if (lang === 'javascript') return `function ${name}(...args)`
  return `def ${name}(*args)`
}

export function extractFunctionName(template, language = 'python') {
  const text = String(template || '')
  const lang = normalizeCodingLanguage(language)
  if (lang === 'python') return (text.match(/def\s+([A-Za-z_]\w*)\s*\(/) || [])[1] || ''
  if (lang === 'java') return (text.match(/(?:public|private|protected)?\s*(?:static\s+)?[A-Za-z_][\w<>\[\]]*\s+([A-Za-z_]\w*)\s*\(/) || [])[1] || ''
  return (text.match(/function\s+([A-Za-z_$][\w$]*)\s*\(/) || [])[1] || ''
}

export function difficultyClass(value) {
  if (value === '简单') return 'ok'
  if (value === '困难') return 'danger'
  return 'warn'
}

export function displayTitle(item, index) {
  return item.title || `未命名题目 ${index + 1}`
}

export function requireText(value, message) {
  if (!String(value || '').trim()) throw new Error(message)
}

export function formatRemainingTime(seconds) {
  const value = Math.max(0, Number(seconds || 0))
  const min = Math.floor(value / 60)
  const sec = value % 60
  return `${String(min).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}
