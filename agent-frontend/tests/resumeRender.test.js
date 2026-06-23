import { describe, it, expect } from 'vitest'
import { applyResumePhotoToHtml, extractManagedResumePhoto, photoTransformStyle, renderResumeMarkdown, inline, escapeHtml, iconSvg, resumePrintCss, stripManagedResumePhoto } from '../src/utils/resumeRender'

describe('escapeHtml', () => {
  it('escapes angle brackets and ampersand', () => {
    expect(escapeHtml('<b>a & b</b>')).toBe('&lt;b&gt;a &amp; b&lt;/b&gt;')
  })
})

describe('iconSvg', () => {
  it('returns svg markup for known icon', () => {
    const svg = iconSvg('github')
    expect(svg).toContain('<svg')
    expect(svg).toContain('icon-github')
  })

  it('resolves alias to base icon path', () => {
    expect(iconSvg('wechat')).toContain('M9 10a6 5')
  })

  it('falls back to fallback icon path for unknown name', () => {
    expect(iconSvg('definitely-not-an-icon')).toContain('M9 12h6')
  })
})

describe('inline', () => {
  it('renders bold, inline code, links, images and icon tokens', () => {
    expect(inline('**重点**')).toContain('<strong>重点</strong>')
    expect(inline('掌握 `Spring Boot` 与 `FastAPI`')).toContain('<code>Spring Boot</code>')
    expect(inline('[博客](https://example.com)')).toContain('<a href="https://example.com"')
    expect(inline('![photo](https://example.com/p.png)')).toContain('src="https://example.com/p.png"')
    expect(inline('icon:github 主页')).toContain('<svg')
  })

  it('escapes plain text before substitution', () => {
    expect(inline('a < b & c')).toBe('a &lt; b &amp; c')
  })
})

describe('renderResumeMarkdown', () => {
  it('renders headings and paragraphs', () => {
    const html = renderResumeMarkdown('## 教育经历\n\n2018-2022 某大学')
    expect(html).toContain('教育经历')
    expect(html).toContain('2018-2022 某大学')
  })

  it('renders list items', () => {
    const html = renderResumeMarkdown('- 第一条\n- 第二条')
    expect(html).toContain('r-list-ul')
    expect(html).toContain('<span class="r-li-body">第一条</span>')
    expect(html).toContain('<span class="r-li-body">第二条</span>')
  })

  it('renders left/right profile columns', () => {
    const md = ':::left\n姓名：林澈\n:::\n:::right\n![photo](https://example.com/p.png)\n:::'
    const html = renderResumeMarkdown(md)
    expect(html).toContain('lr-container')
    expect(html).toContain('林澈')
    expect(html).toContain('https://example.com/p.png')
  })

  it('replaces icon tokens with inline svg', () => {
    const html = renderResumeMarkdown('icon:github 个人主页')
    expect(html).toContain('<svg')
    expect(html).toContain('个人主页')
  })

  it('closes unbalanced containers at the end', () => {
    const html = renderResumeMarkdown(':::header\n# 标题')
    const openTags = (html.match(/<section/g) || []).length
    const closeTags = (html.match(/<\/section>/g) || []).length
    expect(openTags).toBe(closeTags)
  })
})

describe('managed resume photo helpers', () => {
  it('extracts and strips the managed photo from markdown', () => {
    const md = '## 个人资料\n\n:::left\n姓名：林澈\n:::\n:::right\n![证件照](https://example.com/p.png)\n:::'
    expect(extractManagedResumePhoto(md)).toBe('https://example.com/p.png')
    const stripped = stripManagedResumePhoto(md)
    expect(stripped).not.toContain('![证件照]')
    expect(stripped).not.toContain(':::right')
    expect(stripped).toContain('姓名：林澈')
  })

  it('injects the managed photo into rendered html without changing markdown', () => {
    const html = renderResumeMarkdown('## 个人资料\n\n:::left\n姓名：林澈\n:::')
    const withPhoto = applyResumePhotoToHtml(html, 'https://example.com/p.png', { x: 8, y: -6, scale: 1.35 }, { selected: true })
    expect(withPhoto).toContain('class="right"')
    expect(withPhoto).toContain('data-managed-resume-photo="true"')
    expect(withPhoto).toContain('is-selected')
    expect(withPhoto).toContain('src="https://example.com/p.png"')
    expect(withPhoto).toContain('width:126.9px;height:162px;left:8px;top:-6px;')
  })

  it('clamps managed photo transform style', () => {
    expect(photoTransformStyle({ x: 999, y: -999, scale: 9 })).toBe('width:282px;height:360px;left:300px;top:-300px;')
  })
})

describe('resumePrintCss', () => {
  it('returns a4 print layout css', () => {
    const css = resumePrintCss()
    expect(css).toContain('.resume-paper')
    expect(css).toContain('@media print')
  })
})
