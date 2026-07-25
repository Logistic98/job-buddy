import DOMPurify from 'dompurify'

// 统一清洗富文本和简历 HTML，避免外部内容成为 XSS 注入面。
export function sanitizeResumeHtml(html) {
  const input = String(html || '')
  // SSR/构建期无 DOM 时使用保守文本输出，绝不返回未清洗 HTML。
  if (typeof window === 'undefined') {
    return input.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }
  const sanitized = DOMPurify.sanitize(input, {
    USE_PROFILES: { html: true, svg: true },
    ADD_ATTR: ['target', 'rel'],
    FORBID_TAGS: ['script', 'style'],
    FORBID_ATTR: ['srcset'],
  })
  const template = document.createElement('template')
  template.innerHTML = sanitized
  template.content.querySelectorAll('a[target="_blank"]').forEach((link) => {
    link.setAttribute('rel', 'noopener noreferrer')
  })
  return template.innerHTML
}
