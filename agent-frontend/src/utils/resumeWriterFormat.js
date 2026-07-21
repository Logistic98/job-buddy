// 简历编辑器的纯格式化与文件工具：与组件响应式状态无关的无副作用函数，
// 从 ResumeWriter.vue 抽出以收敛组件体积，便于单测复用。

export function clampPhotoNumber(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(max, Math.max(min, number))
}

export function formatDecimal(value) {
  return Number(value.toFixed(3)).toString()
}

export function normalizeFontSizeValue(value) {
  const raw = String(value || '')
    .trim()
    .toLowerCase()
  const match = raw.match(/^(\d+(?:\.\d+)?)(?:px)?$/)
  if (!match) return '12.5px'
  const size = clampPhotoNumber(match[1], 8, 32, 12.5)
  return `${formatDecimal(size)}px`
}

export function normalizeLineHeightValue(value) {
  const raw = String(value || '').trim()
  const match = raw.match(/^(\d+(?:\.\d+)?)$/)
  if (!match) return '1.72'
  return formatDecimal(clampPhotoNumber(match[1], 0.8, 3, 1.72))
}

export function escapeHtmlText(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

export function stripFileExt(name) {
  return String(name || '').replace(/\.[^.]+$/, '')
}

export function sanitizeResumeFileName(value) {
  return String(value || '')
    .trim()
    .replace(/[\\/:*?"<>|]+/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/^-+|-+$/g, '')
}

export function resolveResumeExportFileName(value) {
  return sanitizeResumeFileName(value) || 'resume'
}

export function waitForImages(root) {
  const images = Array.from(root.querySelectorAll('img'))
  return Promise.all(
    images.map((img) =>
      img.complete
        ? Promise.resolve()
        : new Promise((resolve) => {
            img.onload = resolve
            img.onerror = resolve
          }),
    ),
  )
}

export function downloadFile(content, filename, type) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
