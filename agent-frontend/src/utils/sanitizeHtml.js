// 统一的富文本/简历 HTML 清洗：用 DOMPurify 去除 script、事件处理器等可执行内容，
// 仅保留排版所需的标签、class 与 style，供 v-html 渲染前调用，避免简历 Markdown 中
// 透传的原始 HTML（含 AI 生成或在线简历同步内容）成为 XSS 注入面。
import DOMPurify from 'dompurify'

export function sanitizeResumeHtml(html) {
  const input = String(html || '')
  // SSR/构建期无 DOM，DOMPurify 不可用，直接返回原文（渲染发生在浏览器端再清洗）。
  if (typeof window === 'undefined') return input
  return DOMPurify.sanitize(input, { USE_PROFILES: { html: true, svg: true } })
}
