import { describe, it, expect } from 'vitest'
import { sanitizeResumeHtml } from '../src/utils/sanitizeHtml'

describe('sanitizeResumeHtml', () => {
  it('strips script tags', () => {
    const out = sanitizeResumeHtml('<div>hi</div><script>alert(1)</script>')
    expect(out).toContain('hi')
    expect(out.toLowerCase()).not.toContain('<script')
  })

  it('strips inline event handlers', () => {
    const out = sanitizeResumeHtml('<img src="x" onerror="alert(1)">')
    expect(out.toLowerCase()).not.toContain('onerror')
  })

  it('strips javascript: hrefs', () => {
    const out = sanitizeResumeHtml('<a href="javascript:alert(1)">x</a>')
    expect(out.toLowerCase()).not.toContain('javascript:')
  })

  it('keeps resume layout tags, class and style', () => {
    const html = '<section class="resume-header"><div class="left" style="color:#000">姓名</div></section>'
    const out = sanitizeResumeHtml(html)
    expect(out).toContain('resume-header')
    expect(out).toContain('class="left"')
    expect(out).toContain('姓名')
  })

  it('handles empty and nullish input', () => {
    expect(sanitizeResumeHtml('')).toBe('')
    expect(sanitizeResumeHtml(null)).toBe('')
    expect(sanitizeResumeHtml(undefined)).toBe('')
  })
})
