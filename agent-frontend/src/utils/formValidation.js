export function requireText(value, label) {
  const text = String(value ?? '').trim()
  if (!text) throw new Error(`请填写${label}`)
  return text
}

export function validateLength(value, label, { min = 0, max = Infinity, required = false } = {}) {
  const text = String(value ?? '').trim()
  if (required && !text) throw new Error(`请填写${label}`)
  if (!text) return text
  if (text.length < min) throw new Error(`${label}至少需要 ${min} 个字符`)
  if (text.length > max) throw new Error(`${label}不能超过 ${max} 个字符`)
  return text
}

export function validateInteger(value, label, { min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER } = {}) {
  if (value === '' || value == null) throw new Error(`请填写${label}`)
  const number = Number(value)
  if (!Number.isInteger(number)) throw new Error(`${label}必须为整数`)
  if (number < min || number > max) throw new Error(`${label}需在 ${min}-${max} 之间`)
  return number
}

export function validateUsername(value) {
  const text = validateLength(value, '用户名', { min: 3, max: 32, required: true })
  if (!/^[A-Za-z][A-Za-z0-9_-]*$/.test(text)) throw new Error('用户名须以字母开头，仅可包含字母、数字、下划线和连字符')
  return text
}

export function validatePassword(value, label = '密码') {
  const text = validateLength(value, label, { min: 8, max: 16, required: true })
  if (!/[A-Za-z]/.test(text) || !/\d/.test(text)) throw new Error(`${label}必须同时包含字母和数字`)
  return text
}

export function validateCode(value, label) {
  const text = validateLength(value, label, { min: 2, max: 64, required: true })
  if (!/^[A-Za-z][A-Za-z0-9:_-]*$/.test(text))
    throw new Error(`${label}须以字母开头，仅可包含字母、数字、冒号、下划线和连字符`)
  return text
}

export function validateHttpUrl(value, label, { required = false } = {}) {
  const text = String(value ?? '').trim()
  if (!text) {
    if (required) throw new Error(`请填写${label}`)
    return ''
  }
  let parsed
  try {
    parsed = new URL(text)
  } catch (_) {
    throw new Error(`${label}格式不正确`)
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) throw new Error(`${label}仅支持 HTTP 或 HTTPS 地址`)
  return parsed.toString().replace(/\/$/, '')
}

export function validateMonthRange(start, end, label) {
  const startText = String(start || '').trim()
  const endText = String(end || '').trim()
  if (startText && endText && startText > endText) throw new Error(`${label}的开始时间不能晚于结束时间`)
}

export function validateTags(tags, label = '标签', { maxCount = 20, maxLength = 64 } = {}) {
  const rows = Array.isArray(tags) ? tags : []
  if (rows.length > maxCount) throw new Error(`${label}最多添加 ${maxCount} 个`)
  for (const row of rows) {
    const text = String(row?.label || row || '').trim()
    if (!text) throw new Error(`${label}不能为空`)
    if (text.length > maxLength) throw new Error(`单个${label}不能超过 ${maxLength} 个字符`)
  }
  return rows
}

export function validateFile(file, label, { extensions = [], mimeTypes = [], maxBytes, allowEmpty = false } = {}) {
  if (!file) throw new Error(`请选择${label}`)
  if (!allowEmpty && file.size <= 0) throw new Error(`${label}不能为空文件`)
  if (maxBytes && file.size > maxBytes) throw new Error(`${label}不能超过 ${formatBytes(maxBytes)}`)
  const extension =
    String(file.name || '')
      .split('.')
      .pop()
      ?.toLowerCase() || ''
  const normalizedExtensions = extensions.map((item) => item.replace(/^\./, '').toLowerCase())
  const extensionValid = !normalizedExtensions.length || normalizedExtensions.includes(extension)
  const mimeValid = !mimeTypes.length || !file.type || mimeTypes.includes(file.type)
  if (!extensionValid || !mimeValid) throw new Error(`${label}格式不支持`)
  return file
}

function formatBytes(bytes) {
  if (bytes >= 1024 * 1024) return `${Math.round(bytes / 1024 / 1024)}MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)}KB`
  return `${bytes}B`
}
