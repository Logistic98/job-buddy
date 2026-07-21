export async function copyText(text) {
  const value = String(text ?? '')
  if (!value) return false

  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch (_) {
    // Fall through to the selection-based clipboard path.
  }

  if (typeof document === 'undefined') return false
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  textarea.style.pointerEvents = 'none'
  document.body.appendChild(textarea)
  textarea.select()
  textarea.setSelectionRange(0, value.length)
  try {
    return Boolean(document.execCommand?.('copy'))
  } catch (_) {
    return false
  } finally {
    textarea.remove()
  }
}
