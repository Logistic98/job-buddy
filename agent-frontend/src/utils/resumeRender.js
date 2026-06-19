// 简历 Markdown 渲染、图标 SVG 与打印 CSS：纯函数，供编辑器预览、PDF/HTML 导出共用。

export const MANAGED_RESUME_PHOTO_ALT = '证件照'
const DOCUMENT_POSITION_FOLLOWING = 4
const PHOTO_BASE_WIDTH = 94
const PHOTO_BASE_HEIGHT = 120

export function renderResumeMarkdown(source) {
  const lines = String(source || '').split('\n'); const html = []; const stack = []; let listTag = ''; let listIndex = 0; let para = []; let pendingLeftWrapper = false
  const closePendingLeft = () => { if (pendingLeftWrapper) { html.push('</div>'); pendingLeftWrapper = false } }
  const flushPara = () => { if (para.length) { html.push(`<p>${inline(para.join('<br>'))}</p>`); para = [] } }
  const closeList = () => { if (listTag) { html.push('</div>'); listTag = ''; listIndex = 0 } }
  const beforeNormal = () => { flushPara(); closeList(); closePendingLeft() }
  const closeContainer = () => { const current = stack.pop(); if (current === 'left') { html.push('</div>'); pendingLeftWrapper = true } else if (current === 'right') { html.push('</div></div>'); pendingLeftWrapper = false } else if (current === 'header') html.push('</section>'); else if (current === 'title') html.push('</div>'); else if (current) html.push('</div>') }
  for (const raw of lines) { const trimmed = raw.trim(); if (!trimmed) { flushPara(); continue } if (/^:{3,5}\s*left\s*$/.test(trimmed)) { beforeNormal(); html.push('<div class="lr-container"><div class="left">'); stack.push('left'); continue } if (/^:{3,5}\s*right\s*$/.test(trimmed)) { flushPara(); closeList(); if (!pendingLeftWrapper) html.push('<div class="lr-container">'); pendingLeftWrapper = false; html.push('<div class="right">'); stack.push('right'); continue } if (/^:{3,5}\s*header\s*$/.test(trimmed)) { beforeNormal(); html.push('<section class="resume-header">'); stack.push('header'); continue } if (/^:{3,5}\s*title\s*$/.test(trimmed)) { beforeNormal(); html.push('<div class="resume-title-block">'); stack.push('title'); continue } if (/^:{3,5}\s*$/.test(trimmed)) { flushPara(); closeList(); closeContainer(); continue } const heading = trimmed.match(/^(#{1,4})\s+(.+)$/); if (heading) { beforeNormal(); const level = Math.min(heading[1].length + 1, 4); html.push(`<h${level}>${inline(heading[2])}</h${level}>`); continue } if (/^<([a-z]+)(\s|>)/i.test(trimmed)) { beforeNormal(); html.push(trimmed); continue } const ordered = trimmed.match(/^\d+[.、)]\s+(.+)$/); const list = trimmed.match(/^[-*+]\s+(.+)$/); if (ordered || list) { const tag = ordered ? 'ol' : 'ul'; flushPara(); if (listTag !== tag) { closeList(); closePendingLeft(); html.push(`<div class="r-list r-list-${tag}">`); listTag = tag; listIndex = 0 } listIndex += 1; const marker = tag === 'ol' ? `${listIndex}.` : '•'; html.push(`<div class="r-li"><span class="r-li-marker">${marker}</span><span class="r-li-body">${inline((ordered || list)[1])}</span></div>`); continue } closeList(); if (pendingLeftWrapper) closePendingLeft(); para.push(trimmed) }
  flushPara(); closeList(); closePendingLeft(); while (stack.length) closeContainer(); return html.join('\n')
}

export function extractManagedResumePhoto(source) {
  const match = String(source || '').match(/!\[证件照\]\(([^)\n]+)\)/)
  return match?.[1] || ''
}

export function stripManagedResumePhoto(source) {
  return String(source || '')
    .replace(/[ \t]*!\[证件照\]\([^)\n]+\)[ \t]*(?:\n|$)/g, '')
    .replace(/\n?:::\s*right\s*\n\s*:::\s*(?=\n|$)/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd()
}

export function applyResumePhotoToHtml(renderedHtml, photoUrl, transform = {}, options = {}) {
  const url = String(photoUrl || '').trim()
  const markup = String(renderedHtml || '')
  if (!url) return markup
  if (typeof document === 'undefined') return `${photoMarkup(url, transform, options)}\n${markup}`

  const root = document.createElement('div')
  root.innerHTML = markup
  const existingFrame = root.querySelector('[data-managed-resume-photo="true"]')
  const existingImg = existingFrame?.querySelector('img.resume-photo') || root.querySelector('img.resume-photo')
  const frame = existingFrame || document.createElement('span')
  frame.className = `resume-photo-frame${options.selected ? ' is-selected' : ''}`
  frame.dataset.managedResumePhoto = 'true'
  frame.setAttribute('style', photoTransformStyle(transform))

  const img = existingImg || document.createElement('img')
  img.className = 'resume-photo'
  img.alt = MANAGED_RESUME_PHOTO_ALT
  img.src = url
  img.setAttribute('draggable', 'false')
  img.removeAttribute('style')

  if (!frame.contains(img)) frame.appendChild(img)
  applyPhotoHandles(frame, options.selected)
  if (existingFrame) return root.innerHTML
  if (existingImg) existingImg.replaceWith(frame)

  const target = findPhotoTarget(root)
  if (target) {
    target.appendChild(frame)
  } else {
    const wrapper = document.createElement('div')
    wrapper.className = 'resume-floating-photo'
    wrapper.appendChild(frame)
    root.insertBefore(wrapper, root.firstChild)
  }
  return root.innerHTML
}

export function photoTransformStyle(transform = {}) {
  const x = clampNumber(transform.x, -300, 300, 0)
  const y = clampNumber(transform.y, -300, 300, 0)
  const scale = clampNumber(transform.scale, 0.4, 3, 1)
  const width = PHOTO_BASE_WIDTH * scale
  const height = PHOTO_BASE_HEIGHT * scale
  return `width:${width}px;height:${height}px;left:${x}px;top:${y}px;`
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value)
  if (!Number.isFinite(number)) return fallback
  return Math.min(max, Math.max(min, number))
}

function findPhotoTarget(root) {
  const profileHeading = Array.from(root.querySelectorAll('h2,h3,h4')).find(heading => /个人(资料|信息|简历)|基本信息/.test(heading.textContent || ''))
  const containers = Array.from(root.querySelectorAll('.lr-container'))
  const container = profileHeading
    ? containers.find(item => isAfterProfileHeading(item, profileHeading))
    : containers[0]
  if (!container) return null
  let right = Array.from(container.children).find(child => child.classList?.contains('right'))
  if (right && right.textContent.trim()) return null
  if (!right) {
    right = document.createElement('div')
    right.className = 'right'
    container.appendChild(right)
  }
  return right
}

function isAfterProfileHeading(container, heading) {
  return !!(heading.compareDocumentPosition(container) & DOCUMENT_POSITION_FOLLOWING)
}

function applyPhotoHandles(frame, selected) {
  frame.querySelectorAll('.resume-photo-handle').forEach(node => node.remove())
  if (!selected) return
  const handles = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w', 'move']
  handles.forEach(handle => {
    const node = document.createElement('span')
    node.className = `resume-photo-handle handle-${handle}`
    node.dataset.photoHandle = handle
    frame.appendChild(node)
  })
}

function photoHandlesMarkup(selected) {
  if (!selected) return ''
  return ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w', 'move']
    .map(handle => `<span class="resume-photo-handle handle-${handle}" data-photo-handle="${handle}"></span>`)
    .join('')
}

function photoMarkup(url, transform = {}, options = {}) {
  return `<div class="resume-floating-photo"><span class="resume-photo-frame${options.selected ? ' is-selected' : ''}" data-managed-resume-photo="true" style="${photoTransformStyle(transform)}"><img class="resume-photo" alt="${MANAGED_RESUME_PHOTO_ALT}" src="${escapeHtml(url)}" draggable="false">${photoHandlesMarkup(options.selected)}</span></div>`
}

export function inline(value) { return escapeHtml(value).replace(/!\[(.*?)\]\((.+?)\)/g, '<img class="resume-photo" alt="$1" src="$2">').replace(/`([^`]+?)`/g, '<code>$1</code>').replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/\[(.+?)\]\((.+?)\)/g, '<a href="$2" target="_blank">$1</a>').replace(/icon:([a-zA-Z0-9_-]+)/g, (_, name) => iconSvg(name)) }

export function escapeHtml(value) { return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }

export function iconSvg(name) {
  const key = String(name || '').toLowerCase()
  const paths = {
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 7.5h.01"/>',
    phone: '<rect x="8" y="3" width="8" height="18" rx="2"/><path d="M11 18h2"/>',
    email: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M4 7l8 6 8-6"/>',
    email2: '<path d="M3 6h18v12H3z"/><path d="M3 6l9 7 9-7"/>',
    mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M4 7l8 6 8-6"/>',
    blog: '<path d="M4 11l8-7 8 7v9H4z"/><path d="M9 20v-6h6v6"/>',
    blog2: '<path d="M3 12l9-8 9 8"/><path d="M5 10v10h14V10"/><path d="M9 20v-6h6v6"/>',
    github: '<path d="M9 19c-5 1-5-2-7-3m14 6v-4c0-1 0-2-1-3 3 0 6-1 6-6 0-1 0-3-1-4 0-1 0-2 0-4 0 0-1 0-4 2-2-1-6-1-8 0-3-2-4-2-4-2 0 2 0 3 0 4-1 1-1 3-1 4 0 5 3 6 6 6-1 1-1 2-1 3v4"/>',
    weixin: '<path d="M9 10a6 5 0 1 1 3 4l-3 1 1-2a6 5 0 0 1-1-3z"/><path d="M14 13a5 4 0 1 0 3 4l3 1-1-2"/>',
    yuque: '<path d="M5 18c5 0 12-4 14-13-6 1-11 4-14 13z"/><path d="M5 18c3-5 7-8 14-13"/>',
    sifou: '<text x="5" y="17" font-size="14" font-weight="900" fill="currentColor">sf</text>',
    zhihu: '<text x="4" y="17" font-size="13" font-weight="900" fill="currentColor">知</text>',
    gitee: '<circle cx="12" cy="12" r="9"/><path d="M8 12h8M12 8h5M12 16h5"/>',
    weibo: '<path d="M7 14a5 4 0 1 0 10 0 5 4 0 0 0-10 0z"/><path d="M15 7c3 0 5 2 5 5M16 4c4 0 7 3 7 7"/>',
    qq: '<path d="M12 3c-3 0-5 3-5 7 0 3-2 5-2 7 1 2 3 1 4 0 1 2 5 2 6 0 1 1 3 2 4 0 0-2-2-4-2-7 0-4-2-7-5-7z"/>',
    twitter: '<path d="M22 5c-1 1-2 1-3 1 1-1 1-2 1-3-1 1-2 1-3 1a4 4 0 0 0-7 3v1A11 11 0 0 1 3 4s-4 9 5 13c-2 1-4 1-6 1 9 5 20 0 20-11V5z"/>',
    facebook: '<path d="M14 8h3V4h-3c-3 0-5 2-5 5v3H6v4h3v6h4v-6h3l1-4h-4V9c0-1 1-1 1-1z"/>',
    csdn: '<circle cx="12" cy="12" r="9"/><path d="M15 9a4 4 0 1 0 0 6"/>',
    school: '<path d="M3 9l9-5 9 5-9 5z"/><path d="M7 12v5c3 2 7 2 10 0v-5"/>',
    juejin: '<path d="M12 4l6 5-6 5-6-5z"/><path d="M6 13l6 5 6-5"/>',
    zhanku: '<text x="5" y="17" font-size="14" font-weight="900" fill="currentColor">Z</text>',
    tag: '<path d="M20 13l-7 7-10-10V3h7z"/><path d="M7 7h.01"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    star: '<path d="M12 3l3 6 6 1-4.5 4.5 1 6.5-5.5-3-5.5 3 1-6.5L3 10l6-1z"/>',
    code: '<path d="M8 9l-4 3 4 3M16 9l4 3-4 3M14 5l-4 14"/>',
    project: '<path d="M3 7h7l2 3h9v9H3z"/>',
    user: '<circle cx="12" cy="8" r="4" fill="currentColor" stroke="none"/><path d="M4 20a8 8 0 0 1 16 0z" fill="currentColor" stroke="none"/>',
    briefcase: '<rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5h8v2M3 12h18"/>',
    company: '<path d="M4 21V5l8-3 8 3v16"/><path d="M9 8h.01M15 8h.01M9 12h.01M15 12h.01M9 16h.01M15 16h.01"/>',
    certificate: '<circle cx="12" cy="8" r="5"/><path d="M8 13l-2 8 6-3 6 3-2-8"/>',
    award: '<circle cx="12" cy="8" r="5"/><path d="M8 13l-2 8 6-3 6 3-2-8"/>',
    skill: '<path d="M14 7l3 3-8 8H6v-3z"/><path d="M16 5l3 3"/>',
    tool: '<path d="M14.7 6.3a4 4 0 0 0-5 5L4 17v3h3l5.7-5.7a4 4 0 0 0 5-5l-3 3-3-3z"/>',
    database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 2 4 3 8 3s8-1 8-3V5M4 12c0 2 4 3 8 3s8-1 8-3"/>',
    server: '<rect x="4" y="4" width="16" height="6" rx="1"/><rect x="4" y="14" width="16" height="6" rx="1"/><path d="M8 7h.01M8 17h.01"/>',
    cloud: '<path d="M18 18a4 4 0 0 0 0-8 6 6 0 0 0-11-2 5 5 0 0 0-1 10z"/>',
    robot: '<rect x="6" y="8" width="12" height="10" rx="2"/><path d="M12 4v4M9 13h.01M15 13h.01M8 20h8"/>',
    chart: '<path d="M4 19V5M4 19h17"/><rect x="7" y="11" width="3" height="5"/><rect x="12" y="8" width="3" height="8"/><rect x="17" y="4" width="3" height="12"/>',
    rocket: '<path d="M5 19c2-5 6-11 14-14-3 8-9 12-14 14z"/><path d="M8 16l-3 3M14 5l5 5M9 20l-5-5"/>',
    target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>',
    location: '<path d="M12 21s7-5 7-11a7 7 0 1 0-14 0c0 6 7 11 7 11z"/><circle cx="12" cy="10" r="2"/>',
    calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M8 3v4M16 3v4M3 10h18"/>',
    time: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6l4 2"/>',
    money: '<rect x="3" y="6" width="18" height="12" rx="2"/><circle cx="12" cy="12" r="3"/>',
    link: '<path d="M10 13a5 5 0 0 0 7 0l2-2a5 5 0 0 0-7-7l-1 1"/><path d="M14 11a5 5 0 0 0-7 0l-2 2a5 5 0 0 0 7 7l1-1"/>',
    download: '<path d="M12 3v12M7 10l5 5 5-5"/><path d="M5 21h14"/>',
    upload: '<path d="M12 15V3M7 8l5-5 5 5"/><path d="M5 21h14"/>',
    edit: '<path d="M12 20h9"/><path d="M16 4l4 4L8 20H4v-4z"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    heart: '<path d="M20 8a5 5 0 0 0-8-4 5 5 0 0 0-8 4c0 6 8 11 8 11s8-5 8-11z"/>',
    flag: '<path d="M5 21V4h10l1 4h4v9H9l-1-4H5"/>',
    light: '<path d="M9 18h6M10 22h4"/><path d="M8 14a6 6 0 1 1 8 0c-1 1-1 2-1 4H9c0-2 0-3-1-4z"/>',
    book: '<path d="M4 19a3 3 0 0 1 3-3h14v5H7a3 3 0 0 1-3-2z"/><path d="M4 19V5a3 3 0 0 1 3-3h14v14"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-5-5"/>',
    message: '<path d="M4 5h16v11H7l-3 3z"/>',
    meeting: '<path d="M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 20a5 5 0 0 1 10 0M11 20a5 5 0 0 1 10 0"/>',
    growth: '<path d="M4 19h16"/><path d="M5 15l4-4 3 3 7-8"/><path d="M15 6h4v4"/>',
    terminal: '<path d="M4 17l6-5-6-5"/><path d="M12 19h8"/>',
    bug: '<path d="M8 8a4 4 0 0 1 8 0v8a4 4 0 0 1-8 0z"/><path d="M3 13h5M16 13h5M4 7l3 3M20 7l-3 3M4 19l3-3M20 19l-3-3"/>',
    leaf: '<path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z" fill="currentColor" stroke="none"/><path d="M2 21c0-3 1.85-5.36 5.08-6" fill="none" stroke="currentColor"/>',
    gear: '<path d="M19.4 13l1.5 1.2-1.5 2.6-1.9-.6a6.5 6.5 0 0 1-1.6 1l-.4 1.9h-3l-.4-1.9a6.5 6.5 0 0 1-1.6-1l-1.9.6-1.5-2.6L6.6 13a6.6 6.6 0 0 1 0-2L5.1 9.8l1.5-2.6 1.9.6a6.5 6.5 0 0 1 1.6-1l.4-1.9h3l.4 1.9a6.5 6.5 0 0 1 1.6 1l1.9-.6 1.5 2.6L19.4 11a6.6 6.6 0 0 1 0 2z"/><circle cx="12" cy="12" r="2.6"/>',
    fallback: '<rect x="6" y="6" width="12" height="12" rx="3"/><path d="M9 12h6"/>',
  }
  const aliases = { profile:'user', resume:'user', idcard:'user', department:'company', job:'briefcase', position:'briefcase', career:'briefcase', experience:'briefcase', work:'briefcase', team:'meeting', manager:'user', education:'school', degree:'school', honor:'award', language:'code', java:'code', python:'code', ai:'robot', data:'database', analysis:'chart', salary:'money', website:'link', portfolio:'project', wechat:'weixin', linkedin:'link', pdf:'download', word:'book', interview:'meeting', idea:'light', api:'code', settings:'gear', cog:'gear', position2:'gear' }
  const body = paths[key] || paths[aliases[key]] || paths.fallback
  return `<svg class="resume-icon-svg icon-${key}" viewBox="0 0 24 24" aria-hidden="true">${body}</svg>`
}

export function resumePrintCss() { return `@page{size:A4;margin:0}html,body{margin:0;background:#fff;color:#111827;-webkit-print-color-adjust:exact;print-color-adjust:exact}.resume-paper{width:210mm;min-height:297mm;margin:0 auto;background:#fff;color:#111827;padding:14mm 16mm;box-sizing:border-box;font-size:12px;line-height:var(--resume-line-height,1.52);letter-spacing:.01em}.resume-paper.compact{font-size:11.2px;line-height:1.42;padding:12mm 15mm}.resume-paper h2{font-size:18px;margin:0 0 9px;border-bottom:2px solid #111827;padding-bottom:5px;line-height:1.25;break-after:avoid;page-break-after:avoid}.resume-paper h3{font-size:16px;margin:15px 0 7px;border-bottom:1.5px solid #111827;padding-bottom:4px;line-height:1.25;break-after:avoid;page-break-after:avoid}.resume-paper h4{font-size:13px;margin:10px 0 5px;line-height:1.32;break-after:avoid;page-break-after:avoid}.resume-paper p{margin:3px 0;line-height:inherit;break-inside:avoid;page-break-inside:avoid}.resume-paper .r-list{margin:4px 0 7px 0;break-inside:avoid;page-break-inside:avoid}.resume-paper .r-list-ol{padding-left:1.7em}.resume-paper .r-li{display:flex;align-items:flex-start;margin:2px 0;line-height:inherit;break-inside:avoid;page-break-inside:avoid}.resume-paper .r-li-marker{flex:0 0 1.6em;line-height:inherit;white-space:nowrap}.resume-paper .r-li-body{flex:1 1 auto;min-width:0;line-height:inherit}.lr-container{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:18px;align-items:start;break-inside:avoid;page-break-inside:avoid}.left,.right{min-width:0}.right{text-align:right;white-space:nowrap;color:#5b6472}.resume-photo-frame{display:inline-block;position:relative;width:94px;height:120px;border-radius:3px;vertical-align:top;background:#f3f4f6}.resume-photo{width:100%;height:100%;object-fit:cover;border-radius:0;user-select:none;display:block}.resume-floating-photo{float:right;margin:0 0 8px 18px;text-align:right}.resume-icon-svg{display:inline-block;width:12px;height:12px;margin-right:5px;vertical-align:-1px;color:#111827;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.resume-icon-svg text{stroke:none}.resume-page-break-before{break-before:page;page-break-before:always}.resume-paper a{color:#111827;text-decoration:none}.resume-paper strong{font-weight:700}.resume-paper code{font-family:inherit;font-size:.84em;line-height:1.7;background:#f4f6fa;border:1px solid #e3e8f0;border-radius:5px;padding:1px 8px;margin:1px;color:#3f4a5a;white-space:nowrap;display:inline-block;vertical-align:1px}.theme-blue h2,.theme-blue h3{border-color:#2563eb}.theme-orange h2,.theme-orange h3{border-color:#f97316}@media print{body{margin:0;background:#fff}.resume-paper{box-shadow:none;margin:0;width:210mm;min-height:297mm}}` }
